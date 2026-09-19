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

package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SharedMapGroupsTest {

  private static final SharedMapGroups LOBBIES =
      SharedMapGroups.from(List.of(List.of("lobby1", "lobby-2", "lobby2")));

  @Test
  void serversInGroupShareTheirMap() {
    assertTrue(LOBBIES.sameMap("lobby1", "lobby-2"));
    assertTrue(LOBBIES.sameMap("LOBBY1", "lobby2"), "names are not case sensitive");
  }

  @Test
  void serversOutsideItDoNot() {
    assertFalse(LOBBIES.sameMap("lobby1", "spawn"));
    assertFalse(LOBBIES.sameMap("spawn", "aquario"), "neither is in a group");
    assertFalse(LOBBIES.sameMap(null, "lobby1"), "nothing is known about the client's world yet");
  }

  @Test
  void serverAlwaysSharesItsMapWithItself() {
    assertTrue(LOBBIES.sameMap("spawn", "spawn"));
    assertTrue(SharedMapGroups.from(null).sameMap("spawn", "SPAWN"));
  }

  @Test
  void groupsThatSayNothingAreIgnored() {
    final SharedMapGroups odd = SharedMapGroups.from(
        List.of(List.of("alone"), "not-a-list", List.of("a", "b"), List.of()));
    assertFalse(odd.sameMap("alone", "a"));
    assertTrue(odd.sameMap("a", "b"));
  }
}
