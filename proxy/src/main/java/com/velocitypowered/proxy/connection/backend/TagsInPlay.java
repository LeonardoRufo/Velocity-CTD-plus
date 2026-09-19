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

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Hands a backend's tags to a client that stayed in play.
 *
 * <p>A client kept in play for a switch never enters the configuration state the destination sends
 * its tags in, so they would be dropped and the client would keep the previous server's. The play
 * state carries the same packet, with the same body -- it is what a datapack reload sends to a
 * player standing in the world -- so the tags can simply be handed over there instead.</p>
 *
 * <p>This is safe precisely because tags are nothing but lists of registry ids: the proxy only
 * keeps a client in play when the destination's registries are the ones it already holds, so the
 * ids these name mean the same thing on both servers.</p>
 */
final class TagsInPlay {

  // ponytail: 26.1-26.2, the one protocol this network lets in. On any other version the tags are
  // dropped as before, and RegistryFingerprint then demands they match for a client to stay in play.
  private static final int PACKET_ID_26_1 = 0x86;

  private TagsInPlay() {
  }

  /**
   * Returns whether this version's play-state tags packet is known, and so whether differing tags
   * can be resolved by sending them instead of by reconfiguring the client.
   *
   * @param version the client's protocol version
   * @return {@code true} if tags can be sent in play
   */
  static boolean canSend(ProtocolVersion version) {
    return version.noLessThan(ProtocolVersion.MINECRAFT_26_1)
        && version.noGreaterThan(ProtocolVersion.MINECRAFT_26_2);
  }

  /**
   * Encodes tags from a configuration phase as the play-state packet.
   *
   * @param packet  the tags the backend sent
   * @param version the client's protocol version
   * @param alloc   the allocator for the packet
   * @return the packet to write to the client, or {@code null} on a version without a known ID
   */
  static @Nullable ByteBuf encode(TagsUpdatePacket packet, ProtocolVersion version,
                                  ByteBufAllocator alloc) {
    if (!canSend(version)) {
      return null;
    }
    final ByteBuf inPlay = alloc.buffer();
    try {
      ProtocolUtils.writeVarInt(inPlay, PACKET_ID_26_1);
      packet.encode(inPlay, ProtocolUtils.Direction.CLIENTBOUND, version);
      return inPlay;
    } catch (RuntimeException failed) {
      inPlay.release();
      throw failed;
    }
  }
}
