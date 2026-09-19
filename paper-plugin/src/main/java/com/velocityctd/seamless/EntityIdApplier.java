/*
 * Copyright (C) 2026 Velocity-CTD Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocityctd.seamless;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Gives an arriving player the entity ID their client already holds.
 *
 * <p>Bukkit exposes {@code Entity#getEntityId()} but no setter, so this reaches the server's own
 * {@code Entity#setId(int)}. Since Paper 1.20.5 the server runs on Mojang mappings, so that method
 * is reachable by its real name with no remapping step and no version-specific package -- which is
 * also why this works unchanged on Folia and Canvas.</p>
 *
 * <p>Everything is resolved once at startup. If any of it is missing -- an older server, a fork
 * that renames things -- the applier reports itself unavailable and the plugin simply does not
 * offer entity ID reuse, rather than failing at the worst possible moment mid-login.</p>
 */
final class EntityIdApplier {

  private final Logger logger;
  private final Method getHandle;
  private final Method setId;
  private final Method getWorldHandle;
  private final Method getEntityById;
  private final String unavailableReason;

  EntityIdApplier(final Logger logger) {
    this.logger = logger;

    Method handleMethod = null;
    Method setIdMethod = null;
    Method worldHandleMethod = null;
    Method entityByIdMethod = null;
    String failure = null;

    try {
      // CraftPlayer lost its version-suffixed package in 1.20.5, so this is stable across releases.
      final Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
      handleMethod = craftPlayer.getMethod("getHandle");

      final Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity");
      setIdMethod = nmsEntity.getDeclaredMethod("setId", int.class);
      setIdMethod.setAccessible(true);
      // For the check below: an ID already in use belongs to some entity in some world, and the
      // server refuses to add a second one under it.
      worldHandleMethod = Class.forName("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle");
      entityByIdMethod = Class.forName("net.minecraft.server.level.ServerLevel")
          .getMethod("getEntity", int.class);
    } catch (final ClassNotFoundException e) {
      failure = "server internals not found (" + e.getMessage() + "); needs Paper 1.20.5 or newer";
    } catch (final NoSuchMethodException e) {
      failure = "Entity#setId(int) not found (" + e.getMessage() + "); server mappings differ";
    } catch (final RuntimeException e) {
      failure = "could not access server internals: " + e;
    }

    this.getHandle = handleMethod;
    this.setId = setIdMethod;
    this.getWorldHandle = worldHandleMethod;
    this.getEntityById = entityByIdMethod;
    this.unavailableReason = failure;
  }

  /**
   * Returns whether entity ID reuse can work on this server.
   *
   * @return {@code true} if the server internals were resolved
   */
  boolean isAvailable() {
    return unavailableReason == null;
  }

  /**
   * Returns why entity ID reuse is unavailable, or {@code null} if it is available.
   *
   * @return the reason, for logging
   */
  String unavailableReason() {
    return unavailableReason;
  }

  /**
   * Sets a player's entity ID.
   *
   * <p>Must run before the join game packet is built, or the client is told one ID and the server
   * keeps using another.</p>
   *
   * @param player   the arriving player
   * @param entityId the ID their client already holds
   * @return {@code true} if the ID was applied
   */
  boolean apply(final Player player, final int entityId) {
    if (!isAvailable() || entityId <= 0) {
      return false;
    }
    if (isTaken(entityId)) {
      // Forcing it would have the server refuse to add the player to the world, which is a far
      // worse outcome than the loading screen they get by joining with an ID of this server's own.
      logger.warning("Entity ID " + entityId + " is already in use here, so " + player.getName()
          + " joins with a fresh one and gets an ordinary switch");
      return false;
    }
    try {
      setId.invoke(getHandle.invoke(player), entityId);
      return true;
    } catch (final ReflectiveOperationException | RuntimeException e) {
      logger.log(Level.WARNING,
          "Could not set the entity ID for " + player.getName() + "; they will get an ordinary "
              + "switch with a loading screen", e);
      return false;
    }
  }

  /**
   * Returns whether some entity in some world already holds this ID.
   *
   * @param entityId the ID the proxy asked for
   * @return {@code true} if it is taken, or if the question could not be answered
   */
  private boolean isTaken(final int entityId) {
    try {
      for (final World world : Bukkit.getWorlds()) {
        if (getEntityById.invoke(getWorldHandle.invoke(world), entityId) != null) {
          return true;
        }
      }
      return false;
    } catch (final ReflectiveOperationException | RuntimeException e) {
      logger.log(Level.WARNING, "Could not check whether entity ID " + entityId + " is free", e);
      return true;
    }
  }
}
