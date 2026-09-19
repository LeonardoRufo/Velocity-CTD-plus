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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks the scoreboard objectives and teams the current backend has created on the client, so a
 * server switch can remove them again.
 *
 * <p>The configuration state is what normally wipes the client's scoreboard on a switch. With
 * {@code remove-reconfig} the client never enters it, so the previous server's sidebar, teams and
 * nametags outlive the switch, and a destination that creates an objective under a name the client
 * still holds collides with it. BungeeCord, which never had a configuration state, has always had
 * to do this same bookkeeping.</p>
 *
 * <p>Only the start of packets the proxy already forwards undecoded is read, and the buffer is left
 * exactly as it was, so what the client receives never changes. A packet that cannot be read is
 * simply not tracked.</p>
 */
final class ServerScoreboardTracker {

  // ponytail: IDs for 26.1-26.2 only, the one protocol this network lets in. Any other version
  // tracks nothing, which is the same behaviour as without this class. Add a range per protocol
  // when the network upgrades (26.3 shifted the IDs of the packets around these).
  private static final int OBJECTIVE_PACKET_ID = 0x6A;
  private static final int TEAM_PACKET_ID = 0x6D;

  // Shared by both packets: 0 creates, 1 removes; the other methods update what already exists.
  private static final byte METHOD_ADD = 0;
  private static final byte METHOD_REMOVE = 1;

  private final Set<String> objectives = new HashSet<>();
  private final Set<String> teams = new HashSet<>();

  /**
   * Notes an objective or team the backend creates or removes.
   *
   * @param packetId the packet's ID, already read
   * @param packet   the rest of the packet, which the caller rewinds
   * @param version  the client's protocol version
   */
  void observe(int packetId, ByteBuf packet, ProtocolVersion version) {
    if (!isTracked(version)
        || (packetId != OBJECTIVE_PACKET_ID && packetId != TEAM_PACKET_ID)) {
      return;
    }
    final String name = ProtocolUtils.readString(packet);
    final byte method = packet.readByte();
    final Set<String> names = packetId == OBJECTIVE_PACKET_ID ? objectives : teams;
    if (method == METHOD_ADD) {
      names.add(name);
    } else if (method == METHOD_REMOVE) {
      names.remove(name);
    }
  }

  /**
   * Builds the packets that remove everything tracked from the client, and forgets it.
   *
   * @param alloc   the allocator for the packets
   * @param version the client's protocol version
   * @return the removal packets, each positioned at its ID, for the caller to write and release
   */
  List<ByteBuf> drainRemovals(ByteBufAllocator alloc, ProtocolVersion version) {
    final List<ByteBuf> removals = new ArrayList<>(objectives.size() + teams.size());
    if (isTracked(version)) {
      for (String name : objectives) {
        removals.add(removal(alloc, OBJECTIVE_PACKET_ID, name));
      }
      for (String name : teams) {
        removals.add(removal(alloc, TEAM_PACKET_ID, name));
      }
    }
    objectives.clear();
    teams.clear();
    return removals;
  }

  /**
   * Forgets everything tracked, for when the client's scoreboard was wiped some other way.
   */
  void clear() {
    objectives.clear();
    teams.clear();
  }

  private static ByteBuf removal(ByteBufAllocator alloc, int packetId, String name) {
    final ByteBuf packet = alloc.buffer();
    ProtocolUtils.writeVarInt(packet, packetId);
    ProtocolUtils.writeString(packet, name);
    packet.writeByte(METHOD_REMOVE);
    return packet;
  }

  private static boolean isTracked(ProtocolVersion version) {
    return version.noLessThan(ProtocolVersion.MINECRAFT_26_1)
        && version.noGreaterThan(ProtocolVersion.MINECRAFT_26_2);
  }
}
