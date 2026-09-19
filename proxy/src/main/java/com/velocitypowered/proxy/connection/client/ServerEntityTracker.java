/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.velocitypowered.proxy.connection.client;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tracks the entities the current backend has spawned on the client, so a switch that keeps the
 * client's world can take them away again.
 *
 * <p>A client that keeps its world keeps everything in it. Nothing else removes what the previous
 * server spawned -- the destination has never heard of those entities -- so its holograms, NPCs and
 * item displays would hang in the new world, frozen, until something happened to reuse their IDs.
 * The proxy is the only party that saw both sides, so the bookkeeping belongs here.</p>
 *
 * <p>Only the entity ID is read, off packets the proxy forwards undecoded, and the buffer is left
 * exactly as it was.</p>
 */
final class ServerEntityTracker {

  // ponytail: 26.1-26.2, as in ServerScoreboardTracker. On another version nothing is tracked and
  // nothing is removed, which is where this started.
  private static final int SPAWN_ENTITY_PACKET_ID = 0x01;
  private static final int REMOVE_ENTITIES_PACKET_ID = 0x4D;

  private final IntSet spawned = new IntOpenHashSet();

  /**
   * Notes an entity the backend spawns on, or removes from, the client.
   *
   * @param packetId the packet's ID, already read
   * @param packet   the rest of the packet, which the caller rewinds
   * @param version  the client's protocol version
   */
  void observe(int packetId, ByteBuf packet, ProtocolVersion version) {
    if (!isTracked(version)) {
      return;
    }
    if (packetId == SPAWN_ENTITY_PACKET_ID) {
      spawned.add(ProtocolUtils.readVarInt(packet));
    } else if (packetId == REMOVE_ENTITIES_PACKET_ID) {
      final int count = ProtocolUtils.readVarInt(packet);
      for (int i = 0; i < count; i++) {
        spawned.remove(ProtocolUtils.readVarInt(packet));
      }
    }
  }

  /**
   * Builds the one packet that removes everything tracked from the client, and forgets it.
   *
   * @param alloc   the allocator for the packet
   * @param version the client's protocol version
   * @return the removal packet, or {@code null} when there is nothing to remove
   */
  @Nullable ByteBuf drainRemovals(ByteBufAllocator alloc, ProtocolVersion version) {
    if (spawned.isEmpty() || !isTracked(version)) {
      spawned.clear();
      return null;
    }
    final ByteBuf removal = alloc.buffer();
    ProtocolUtils.writeVarInt(removal, REMOVE_ENTITIES_PACKET_ID);
    ProtocolUtils.writeVarInt(removal, spawned.size());
    for (IntIterator ids = spawned.iterator(); ids.hasNext(); ) {
      ProtocolUtils.writeVarInt(removal, ids.nextInt());
    }
    spawned.clear();
    return removal;
  }

  /**
   * Forgets everything tracked, for when the client's world was torn down some other way.
   */
  void clear() {
    spawned.clear();
  }

  private static boolean isTracked(ProtocolVersion version) {
    return version.noLessThan(ProtocolVersion.MINECRAFT_26_1)
        && version.noGreaterThan(ProtocolVersion.MINECRAFT_26_2);
  }
}
