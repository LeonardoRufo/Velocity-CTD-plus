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

import static com.velocitypowered.proxy.connection.backend.LoginSessionHandler.clientHoldsRegistriesOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket;
import com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistryFingerprintTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_26_2;

  // Stand-ins for what a backend sends: the vanilla biome registry, and the same plus a datapack's.
  private static final byte[] VANILLA_BIOMES = {1, 2, 3, 4};
  private static final byte[] BIOMES_WITH_DATAPACK = {1, 2, 3, 4, 5};
  private static final Map<String, Map<String, int[]>> TAGS =
      ImmutableMap.of("minecraft:block", ImmutableMap.of("minecraft:climbable", new int[] {7, 9}));
  private static final Map<String, Map<String, int[]>> OTHER_TAGS =
      ImmutableMap.of("minecraft:block", ImmutableMap.of("minecraft:climbable", new int[] {7}));

  @Test
  void sameRegistriesAndTagsGiveTheSameFingerprint() {
    assertEquals(fingerprint(VANILLA_BIOMES, TAGS), fingerprint(VANILLA_BIOMES, TAGS));
  }

  @Test
  void datapackBiomeChangesTheFingerprint() {
    assertNotEquals(fingerprint(VANILLA_BIOMES, TAGS), fingerprint(BIOMES_WITH_DATAPACK, TAGS));
  }

  @Test
  void differentTagsDoNotCostTheClientItsPlayState() {
    // On a version whose play-state tags packet the proxy knows, tags are sent to the client
    // rather than demanded of the destination, so they cannot keep it from staying in play.
    assertEquals(fingerprint(VANILLA_BIOMES, TAGS), fingerprint(VANILLA_BIOMES, OTHER_TAGS));
  }

  @Test
  void differentTagsCountOnVersionsTheyCannotBeSentOn() {
    final ProtocolVersion old = ProtocolVersion.MINECRAFT_1_21_11;
    assertNotEquals(fingerprint(VANILLA_BIOMES, TAGS, old), fingerprint(VANILLA_BIOMES, OTHER_TAGS, old));
    assertEquals(fingerprint(VANILLA_BIOMES, TAGS, old), fingerprint(VANILLA_BIOMES, TAGS, old));
  }

  @Test
  void namesTheRegistriesTwoBackendsDoNotAgreeOn() {
    final Map<String, String> lobby =
        Map.of("minecraft:worldgen/biome", "aaa", "minecraft:dialog", "bbb", "(tags)", "ccc");
    final Map<String, String> world =
        Map.of("minecraft:worldgen/biome", "zzz", "minecraft:dialog", "bbb", "(tags)", "ccc");
    assertEquals(List.of("minecraft:worldgen/biome"),
        RegistryFingerprint.differences(world, lobby));
    assertEquals(List.of(), RegistryFingerprint.differences(lobby, lobby));
    assertEquals(List.of("minecraft:enchantment"), RegistryFingerprint.differences(
        Map.of("minecraft:enchantment", "aaa"), Map.of()), "one side missing it entirely");
  }

  @Test
  void clientStaysInPlayOnlyWhenItHoldsWhatTheDestinationSends() {
    final VelocityRegisteredServer spawn = server("spawn");
    final VelocityRegisteredServer world = server("world");
    spawn.setRegistryFingerprint(fingerprint(VANILLA_BIOMES, TAGS));
    world.setRegistryFingerprint(fingerprint(BIOMES_WITH_DATAPACK, TAGS));

    final ConnectedPlayer fromLobby = player(fingerprint(VANILLA_BIOMES, TAGS));
    assertTrue(clientHoldsRegistriesOf(fromLobby, spawn));
    assertFalse(clientHoldsRegistriesOf(fromLobby, world), "the datapack biomes must be sent");
    assertFalse(clientHoldsRegistriesOf(fromLobby, server("aquario")), "never seen: configure it");
    assertFalse(clientHoldsRegistriesOf(player(null), spawn), "nothing recorded for the client");
  }

  private static String fingerprint(byte[] registries, Map<String, Map<String, int[]>> tags) {
    return fingerprint(registries, tags, VERSION);
  }

  private static String fingerprint(byte[] registries, Map<String, Map<String, int[]>> tags,
                                    ProtocolVersion version) {
    final RegistrySyncPacket registryPacket = new RegistrySyncPacket();
    registryPacket.decode(Unpooled.wrappedBuffer(registries), ProtocolUtils.Direction.CLIENTBOUND, version);
    final RegistryFingerprint fingerprint = new RegistryFingerprint();
    fingerprint.add(registryPacket);
    fingerprint.add(new TagsUpdatePacket(tags), version);
    registryPacket.release();
    return fingerprint.finish(version);
  }

  private static VelocityRegisteredServer server(String name) {
    return new VelocityRegisteredServer(null,
        new ServerInfo(name, InetSocketAddress.createUnresolved("localhost", 25565)));
  }

  private static ConnectedPlayer player(String registries) {
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getClientRegistryFingerprint()).thenReturn(registries);
    return player;
  }
}
