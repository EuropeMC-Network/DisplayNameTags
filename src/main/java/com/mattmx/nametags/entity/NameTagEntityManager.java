package com.mattmx.nametags.entity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.retrooper.packetevents.util.Vector3f;
import com.mattmx.nametags.NameTags;
import com.mattmx.nametags.event.NameTagEntityCreateEvent;
import com.mattmx.nametags.event.NameTagEntityPreSpawnEvent;
import io.github.retrooper.packetevents.util.folia.FoliaScheduler;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import me.tofaa.entitylib.meta.display.TextDisplayMeta;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class NameTagEntityManager {

    private final Cache<UUID, NameTagEntity> nameTagCache = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(1))
        .removalListener(this::handleRemoval)
        .build();

    private final ConcurrentHashMap<Integer, NameTagEntity> nameTagEntityByEntityId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, NameTagEntity> nameTagEntityByPassengerEntityId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, int[]> lastSentPassengers = new ConcurrentHashMap<>();

    private @NotNull BiConsumer<Entity, TextDisplayMeta> defaultProvider = (entity, meta) -> {
        meta.setText(entity.name());
        meta.setTranslation(new Vector3f(0f, 0.2f, 0f));
        meta.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
        meta.setViewRange(50f);
    };

    public @NotNull NameTagEntity getOrCreateNameTagEntity(@NotNull Entity entity) {
        final NameTagEntity existing = nameTagCache.getIfPresent(entity.getUniqueId());
        if (existing != null && existing.getBukkitEntity().getEntityId() != entity.getEntityId()) {
            if (nameTagCache.asMap().remove(entity.getUniqueId(), existing)) {
                discard(existing);
            }
        }

        NameTagEntity tagEntity = nameTagCache.get(entity.getUniqueId(), uuid -> {
            NameTagEntity newlyCreated = new NameTagEntity(entity);

            newlyCreated.getPassenger().consumeEntityMeta(TextDisplayMeta.class, meta ->
                defaultProvider.accept(entity, meta)
            );

            Bukkit.getPluginManager().callEvent(new NameTagEntityPreSpawnEvent(newlyCreated));

            newlyCreated.initialize();

            Bukkit.getPluginManager().callEvent(new NameTagEntityCreateEvent(newlyCreated));

            final NameTagEntity previous = nameTagEntityByEntityId.put(entity.getEntityId(), newlyCreated);
            nameTagEntityByPassengerEntityId.put(newlyCreated.getPassenger().getEntityId(), newlyCreated);

            if (previous != null && previous != newlyCreated) {
                nameTagEntityByPassengerEntityId.remove(previous.getPassenger().getEntityId(), previous);
                previous.destroy();
            }

            return newlyCreated;
        });
        return Objects.requireNonNull(tagEntity, "Cache.get(…) unexpectedly returned null for UUID " + entity.getUniqueId());
    }

    public @Nullable NameTagEntity removeEntity(@NotNull Entity entity) {
        lastSentPassengers.remove(entity.getEntityId());

        // Unlink from the ID map before the cache, so an expired entry can't be restored afterwards (see restore()).
        final NameTagEntity removed = nameTagEntityByEntityId.remove(entity.getEntityId());
        if (removed != null) {
            nameTagEntityByPassengerEntityId.remove(removed.getPassenger().getEntityId(), removed);
        }

        final NameTagEntity cached = nameTagCache.asMap().remove(entity.getUniqueId());
        if (cached != null && cached != removed) {
            if (removed == null) {
                unlink(cached);
                return cached;
            }
            discard(cached);
        }

        return removed;
    }

    public @Nullable NameTagEntity getNameTagEntity(@NotNull Entity entity) {
        return nameTagCache.getIfPresent(entity.getUniqueId());
    }

    public @Nullable NameTagEntity getNameTagEntityByUUID(UUID uuid) {
        return nameTagCache.getIfPresent(uuid);
    }

    public @Nullable NameTagEntity getNameTagEntityById(int entityId) {
        return nameTagEntityByEntityId.get(entityId);
    }

    public @Nullable NameTagEntity getNameTagEntityByTagEntityId(int tagEntityId) {
        return nameTagEntityByPassengerEntityId.get(tagEntityId);
    }

    public @NotNull Map<UUID, NameTagEntity> getMappedEntities() {
        return nameTagCache.asMap();
    }

    public @NotNull Collection<NameTagEntity> getAllEntities() {
        return nameTagCache.asMap().values();
    }

    public void setDefaultProvider(@NotNull BiConsumer<Entity, TextDisplayMeta> consumer) {
        this.defaultProvider = consumer;
    }

    public void setLastSentPassengers(int entityId, int[] passengers) {
        this.lastSentPassengers.put(entityId, passengers);
    }

    public void removeLastSentPassengersCache(int entityId) {
        this.lastSentPassengers.remove(entityId);
    }

    public @NotNull Optional<int[]> getLastSentPassengers(int entityId) {
        return Optional.ofNullable(this.lastSentPassengers.get(entityId));
    }

    public int getCacheSize() {
        return nameTagCache.asMap().size();
    }

    public int getEntityIdMapSize() {
        return nameTagEntityByEntityId.size();
    }

    public int getPassengerIdMapSize() {
        return nameTagEntityByPassengerEntityId.size();
    }

    public int getLastSentPassengersSize() {
        return lastSentPassengers.size();
    }

    private void handleRemoval(UUID uuid, NameTagEntity tagEntity, RemovalCause cause) {
        if (cause != RemovalCause.EXPIRED || tagEntity == null) return;

        Entity entity = tagEntity.getBukkitEntity();

        // isConnected() is tied to this Player instance, isOnline() stays true after the player rejoins.
        if (entity instanceof Player player) {
            if (player.isConnected()) {
                restore(uuid, tagEntity);
            } else {
                discard(tagEntity);
            }
        } else {
            final NameTags plugin = NameTags.getInstance();

            if (!plugin.isEnabled()) {
                tagEntity.destroy();
                return;
            }

            FoliaScheduler.getEntityScheduler().execute(
                entity,
                plugin,
                () -> {
                    if (entity.isValid()) {
                        restore(uuid, tagEntity);
                    } else {
                        discard(tagEntity);
                    }
                },
                () -> discard(tagEntity),
                1L
            );
        }
    }

    private void restore(UUID uuid, NameTagEntity tagEntity) {
        // Only put it back while it is still tracked, otherwise removeEntity() ran since it expired and we'd resurrect it.
        // Holding the ID map's lock for this entry makes this atomic with removeEntity(), which unlinks it first.
        nameTagEntityByEntityId.computeIfPresent(tagEntity.getBukkitEntity().getEntityId(), (id, current) -> {
            if (current == tagEntity) {
                this.nameTagCache.asMap().putIfAbsent(uuid, tagEntity);
            }
            return current;
        });
    }

    // Removes by the tag's own IDs, never by UUID, which may now belong to a newer entity's tag.
    private void unlink(NameTagEntity tagEntity) {
        final int entityId = tagEntity.getBukkitEntity().getEntityId();
        nameTagEntityByEntityId.remove(entityId, tagEntity);
        nameTagEntityByPassengerEntityId.remove(tagEntity.getPassenger().getEntityId(), tagEntity);
        lastSentPassengers.remove(entityId);
    }

    private void discard(NameTagEntity tagEntity) {
        unlink(tagEntity);
        tagEntity.destroy();
    }
}
