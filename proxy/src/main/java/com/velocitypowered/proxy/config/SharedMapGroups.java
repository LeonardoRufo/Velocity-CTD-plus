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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The servers the operator says stand on the same map.
 *
 * <p>Keeping a client's world across a switch is invisible when the destination looks like where the
 * player already is, and is a map dissolving in front of them when it does not: the client holds the
 * old chunks until the new ones arrive. Only the operator knows which of their servers are copies of
 * one another, so they say, and {@code keep-client-world-on-switch} then applies within a group and
 * nowhere else.</p>
 */
public final class SharedMapGroups {

  private static final SharedMapGroups NONE = new SharedMapGroups(Map.of());

  /** Server name, lowercased, to the group it belongs to. */
  private final Map<String, Integer> groupByServer;

  private SharedMapGroups(Map<String, Integer> groupByServer) {
    this.groupByServer = groupByServer;
  }

  /**
   * Reads the groups as configured: a list of lists of server names.
   *
   * @param configured what the configuration holds, or {@code null} when it holds nothing
   * @return the groups, empty when nothing usable was configured
   */
  public static SharedMapGroups from(@Nullable List<?> configured) {
    if (configured == null || configured.isEmpty()) {
      return NONE;
    }
    final Map<String, Integer> groupByServer = new HashMap<>();
    int group = 0;
    for (Object entry : configured) {
      final List<String> names = namesOf(entry);
      if (names.size() < 2) {
        continue; // A server always shares a map with itself; a group of one says nothing.
      }
      for (String name : names) {
        groupByServer.put(name.toLowerCase(Locale.ROOT), group);
      }
      group++;
    }
    return groupByServer.isEmpty() ? NONE : new SharedMapGroups(Map.copyOf(groupByServer));
  }

  private static List<String> namesOf(@Nullable Object entry) {
    if (!(entry instanceof List<?> list)) {
      return List.of();
    }
    final List<String> names = new ArrayList<>(list.size());
    for (Object name : list) {
      if (name instanceof String text && !text.isBlank()) {
        names.add(text);
      }
    }
    return names;
  }

  /**
   * Returns whether a client on {@code from} can keep its world when it moves to {@code to}.
   *
   * @param from the server the client's world came from
   * @param to   the server it is switching to
   * @return {@code true} if the two stand on the same map
   */
  public boolean sameMap(@Nullable String from, @Nullable String to) {
    if (from == null || to == null) {
      return false;
    }
    if (from.equalsIgnoreCase(to)) {
      return true; // The same server, under the same name or another one.
    }
    final Integer group = groupByServer.get(from.toLowerCase(Locale.ROOT));
    return group != null && group.equals(groupByServer.get(to.toLowerCase(Locale.ROOT)));
  }
}
