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
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Reduces the registries and tags a backend sends in its configuration phase to a hash.
 *
 * <p>With {@code remove-reconfig} the client stays in play across a switch and keeps the registries
 * and tags an earlier server sent it, while the destination's are swallowed. That is only safe when
 * the two are identical, and running the same Minecraft version is not enough: a datapack that adds
 * a single biome on one backend changes what the client needs to decode that server's chunks.
 * Hashing what each backend sends, and what each client last received, lets the proxy leave the
 * client in play only when nothing would change.</p>
 */
final class RegistryFingerprint {

  private final MessageDigest digest;

  RegistryFingerprint() {
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform is required to provide SHA-256", e);
    }
  }

  void add(RegistrySyncPacket packet) {
    digest.update(packet.content().nioBuffer());
  }

  void add(TagsUpdatePacket packet, ProtocolVersion version) {
    // Decoded, but into maps that keep the wire order, so encoding it again gives the bytes as sent.
    final ByteBuf encoded = Unpooled.buffer();
    try {
      packet.encode(encoded, ProtocolUtils.Direction.CLIENTBOUND, version);
      digest.update(encoded.nioBuffer());
    } finally {
      encoded.release();
    }
  }

  /**
   * Returns the hash of everything added. Call once, when the configuration phase finishes.
   *
   * @return the hash, in hex
   */
  String finish() {
    return HexFormat.of().formatHex(digest.digest());
  }
}
