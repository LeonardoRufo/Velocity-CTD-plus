/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.proxy.player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the entity ID a client currently uses for its own player.
 *
 * <p>A seamless, world-preserving server switch requires the destination backend to give the
 * player the entity ID their client already holds. The backend cannot work that ID out on its own,
 * so a coordinating plugin reads it here and passes it along -- for example in the plugin message
 * it already sends to tell the backend where a player is going -- and the backend then applies it
 * before the player is added to the world.</p>
 *
 * <p>When the ID does not match, the proxy falls back to an ordinary switch, so a plugin that gets
 * this wrong costs the player a loading screen rather than a broken session.</p>
 */
public final class ClientWorldSwitches {

  /**
   * Where the IDs handed to backends start.
   *
   * <p>A backend counts its own entities up from zero and is restarted long before it could reach
   * this, and the libraries that fake entities with packets count down from the top of the range,
   * so an ID from here belongs to the player it was given to and to nobody else. That matters
   * because a server refuses to add an entity whose ID is already taken -- the arriving player
   * would simply never enter the world.</p>
   */
  public static final int NETWORK_ENTITY_ID_BASE = 1_000_000_000;

  /** Where they stop, leaving the range they occupy far from either counter. */
  private static final int NETWORK_ENTITY_ID_CEILING = 1_100_000_000;

  private static final ConcurrentHashMap<UUID, Integer> CLIENT_ENTITY_IDS = new ConcurrentHashMap<>();

  private static final ConcurrentHashMap<UUID, Integer> NETWORK_ENTITY_IDS = new ConcurrentHashMap<>();

  private static final AtomicInteger NEXT_NETWORK_ENTITY_ID =
      new AtomicInteger(NETWORK_ENTITY_ID_BASE);

  private ClientWorldSwitches() {
  }

  /**
   * Returns the entity ID every backend should give this player, allocating one on first use.
   *
   * <p>Letting each backend issue its own would work until one of them handed the player an ID it
   * had already given to a hologram or a dropped item. One ID per player, from a range no backend
   * allocates out of, removes that whole class of collision -- and it holds for the first server
   * too, so the ID never changes for the length of the session.</p>
   *
   * @param playerId the player
   * @return the entity ID to ask backends for
   */
  public static int networkEntityId(UUID playerId) {
    return NETWORK_ENTITY_IDS.computeIfAbsent(playerId, id -> NEXT_NETWORK_ENTITY_ID.updateAndGet(
        previous -> previous >= NETWORK_ENTITY_ID_CEILING ? NETWORK_ENTITY_ID_BASE : previous + 1));
  }

  /**
   * Returns the entity ID the client currently uses for {@code playerId}.
   *
   * @param playerId the player whose client entity ID is wanted
   * @return the entity ID the client holds, or {@code 0} if the player has not finished joining
   */
  public static int clientEntityId(UUID playerId) {
    return CLIENT_ENTITY_IDS.getOrDefault(playerId, 0);
  }

  /**
   * Records the entity ID most recently presented to a client.
   *
   * <p>Called by the proxy. Plugins want {@link #clientEntityId(UUID)}.</p>
   *
   * @param playerId the client that received the ID
   * @param entityId the entity ID from the join game packet it was sent
   */
  public static void rememberClientEntityId(UUID playerId, int entityId) {
    if (entityId > 0) {
      CLIENT_ENTITY_IDS.put(playerId, entityId);
    }
  }

  /**
   * Drops the recorded state for a disconnected player.
   *
   * <p>Called by the proxy.</p>
   *
   * @param playerId the disconnected player
   */
  public static void forget(UUID playerId) {
    CLIENT_ENTITY_IDS.remove(playerId);
    NETWORK_ENTITY_IDS.remove(playerId);
  }
}
