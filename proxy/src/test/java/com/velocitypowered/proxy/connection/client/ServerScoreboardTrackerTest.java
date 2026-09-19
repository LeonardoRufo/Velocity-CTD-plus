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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerScoreboardTrackerTest {

  private static final ProtocolVersion V26_2 = ProtocolVersion.MINECRAFT_26_2;
  private static final int OBJECTIVE = 0x6A;
  private static final int TEAM = 0x6D;

  @Test
  void removesWhatTheServerCreatedAndDidNotRemove() {
    final ServerScoreboardTracker tracker = new ServerScoreboardTracker();
    observe(tracker, packet(OBJECTIVE, "fb-1a2b", 0, 7, 7, 7));
    observe(tracker, packet(TEAM, "fb-1a2b:0", 0, 1, 2));
    observe(tracker, packet(TEAM, "ranking_npcs", 0));
    observe(tracker, packet(TEAM, "ranking_npcs", 2, 9)); // update: still there
    observe(tracker, packet(TEAM, "fb-1a2b:0", 1)); // removed by the server itself

    assertEquals(Set.of("objective fb-1a2b", "team ranking_npcs"), removals(tracker));
    assertEquals(Set.of(), removals(tracker), "draining forgets what was removed");
  }

  @Test
  void leavesTheForwardedPacketExactlyAsItWas() {
    final ServerScoreboardTracker tracker = new ServerScoreboardTracker();
    for (ByteBuf buf : List.of(
        packet(OBJECTIVE, "sidebar", 0, 1, 2, 3),
        packet(0x2C, "not-a-scoreboard-packet", 0),
        truncated(packet(TEAM, "nametag", 0)))) {
      buf.readerIndex(0);
      final byte[] before = ByteBufUtil.getBytes(buf);
      observe(tracker, buf);
      assertEquals(0, buf.readerIndex());
      assertArrayEquals(before, ByteBufUtil.getBytes(buf));
    }
  }

  @Test
  void ignoresMalformedPackets() {
    final ServerScoreboardTracker tracker = new ServerScoreboardTracker();
    observe(tracker, truncated(packet(TEAM, "nametag", 0)));
    observe(tracker, Unpooled.buffer());
    assertEquals(Set.of(), removals(tracker));
  }

  @Test
  void theProxyForwardsThesePacketsUndecoded() {
    // The tracker only sees what reaches handleUnknown; a packet class registered on these IDs
    // would take them away from it without anything failing.
    for (ProtocolVersion version : List.of(ProtocolVersion.MINECRAFT_26_1, V26_2)) {
      final StateRegistry.PacketRegistry.ProtocolRegistry registry =
          StateRegistry.PLAY.getProtocolRegistry(ProtocolUtils.Direction.CLIENTBOUND, version);
      assertNull(registry.createPacket(OBJECTIVE), "objective packet decoded on " + version);
      assertNull(registry.createPacket(TEAM), "team packet decoded on " + version);
    }
  }

  @Test
  void tracksNothingOnProtocolsWhoseIdsItDoesNotKnow() {
    final ServerScoreboardTracker tracker = new ServerScoreboardTracker();
    tracker.observe(packet(OBJECTIVE, "sidebar", 0), ProtocolVersion.MINECRAFT_1_21_11);
    tracker.observe(packet(OBJECTIVE, "sidebar", 0), ProtocolVersion.MINECRAFT_26_3);
    assertTrue(tracker.drainRemovals(UnpooledByteBufAllocator.DEFAULT, ProtocolVersion.MINECRAFT_26_3).isEmpty());
  }

  private static void observe(ServerScoreboardTracker tracker, ByteBuf packet) {
    tracker.observe(packet, V26_2);
  }

  /** A packet as forwarded: ID, name, method, then an arbitrary rest the tracker must not care about. */
  private static ByteBuf packet(int packetId, String name, int method, int... rest) {
    final ByteBuf buf = Unpooled.buffer();
    ProtocolUtils.writeVarInt(buf, packetId);
    ProtocolUtils.writeString(buf, name);
    buf.writeByte(method);
    for (int b : rest) {
      buf.writeByte(b);
    }
    return buf;
  }

  private static ByteBuf truncated(ByteBuf packet) {
    return packet.writerIndex(packet.writerIndex() - 2);
  }

  /** Decodes each removal packet back into "objective name" or "team name", checking it is a removal. */
  private static Set<String> removals(ServerScoreboardTracker tracker) {
    final Set<String> decoded = new HashSet<>();
    for (ByteBuf removal : tracker.drainRemovals(UnpooledByteBufAllocator.DEFAULT, V26_2)) {
      final int packetId = ProtocolUtils.readVarInt(removal);
      assertTrue(packetId == OBJECTIVE || packetId == TEAM);
      final String name = ProtocolUtils.readString(removal);
      assertEquals(1, removal.readByte(), "method 1 removes");
      assertEquals(0, removal.readableBytes(), "a removal carries nothing after the method");
      decoded.add((packetId == OBJECTIVE ? "objective " : "team ") + name);
      removal.release();
    }
    return decoded;
  }
}
