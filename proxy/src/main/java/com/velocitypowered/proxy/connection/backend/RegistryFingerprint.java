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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  /** What is hashed under, for the tags, which are not a registry of their own. */
  private static final String TAGS = "(tags)";

  private final MessageDigest registries = sha256();

  private final MessageDigest tags = sha256();

  /** One hash per registry, in the order sent, to name what differs between two backends. */
  private final Map<String, String> perRegistry = new LinkedHashMap<>();

  void add(RegistrySyncPacket packet) {
    final ByteBuf content = packet.content();
    registries.update(content.nioBuffer());
    // Each of these carries one registry, named first, so the name comes off a read-only view.
    perRegistry.put(nameOf(content), hashOf(content));
  }

  void add(TagsUpdatePacket packet, ProtocolVersion version) {
    // Decoded, but into maps that keep the wire order, so encoding it again gives the bytes as sent.
    final ByteBuf encoded = Unpooled.buffer();
    try {
      packet.encode(encoded, ProtocolUtils.Direction.CLIENTBOUND, version);
      tags.update(encoded.nioBuffer());
      perRegistry.put(TAGS, hashOf(encoded));
    } finally {
      encoded.release();
    }
  }

  /**
   * Returns the name the packet gives its registry, or a placeholder when it cannot be read.
   *
   * @param content the packet's payload, left untouched
   * @return the registry name
   */
  private static String nameOf(ByteBuf content) {
    try {
      return ProtocolUtils.readString(content.duplicate());
    } catch (RuntimeException unreadable) {
      return "(unnamed " + content.readableBytes() + " bytes)";
    }
  }

  private static String hashOf(ByteBuf content) {
    final MessageDigest one = sha256();
    one.update(content.nioBuffer());
    return HexFormat.of().formatHex(one.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform is required to provide SHA-256", e);
    }
  }

  /**
   * Returns the registries the two do not agree on, named.
   *
   * @param mine  one side's per-registry hashes
   * @param other the other side's, from another backend or from what a client holds
   * @return the differing registry names, in the order sent
   */
  static List<String> differences(Map<String, String> mine, Map<String, String> other) {
    final List<String> differing = new ArrayList<>();
    for (Map.Entry<String, String> entry : mine.entrySet()) {
      if (!entry.getValue().equals(other.get(entry.getKey()))) {
        differing.add(entry.getKey());
      }
    }
    for (String name : other.keySet()) {
      if (!mine.containsKey(name)) {
        differing.add(name);
      }
    }
    return differing;
  }

  /**
   * Returns the hash of each registry sent, by name.
   *
   * @return the per-registry hashes
   */
  Map<String, String> perRegistry() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(perRegistry));
  }

  /**
   * Returns the hash of what a client has to already hold for it to stay in play. Call once, when
   * the configuration phase finishes.
   *
   * <p>The registries always count. The tags only do on a version whose play-state tags packet the
   * proxy does not know, since anywhere else differing tags are sent to the client instead of
   * costing it the configuration state.</p>
   *
   * @param version the client's protocol version
   * @return the hash, in hex
   */
  String finish(ProtocolVersion version) {
    final String registriesHash = HexFormat.of().formatHex(registries.digest());
    return TagsInPlay.canSend(version)
        ? registriesHash
        : registriesHash + ':' + HexFormat.of().formatHex(tags.digest());
  }
}
