/*
 * dCache - http://www.dcache.org/
 *
 * Copyright (C) 2016 - 2020 Deutsches Elektronen-Synchrotron
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.dcache.qos.services.verifier.data.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Arrays.asList;

/**
 * Base class providing common SQL processing for queries.
 */
abstract class JdbcCriterion {
  final StringBuilder predicate = new StringBuilder();
  final List<Object> arguments = new ArrayList<>();

  /**
   * Used for GROUP BY.
   */
  protected String classifier = "msg_type";

  /**
   * Used for ORDER BY.
   */
  protected String sorter = "updated";

  /**
   * Used for ORDER BY; reverse interpreted as DESC, otherwise ASC
   */
  protected Boolean reverse;

  public String getPredicate() {
    return predicate.length() == 0 ? "true" : predicate.toString();
  }

  public List<Object> getArguments() {
    return arguments;
  }

  public Object[] getArgumentsAsArray() {
    return arguments.toArray(Object[]::new);
  }

  public String orderBy() {
    return sorter;
  }

  public String groupBy() {
    return classifier;
  }

  public Boolean reverse() { return reverse; }

  public String toString() {
    return "JdbcCriterion{(" + predicate + ")(" + arguments + ")}";
  }

  /**
   * Constructs a single value clause, optionally prefixing it with AND.
   *
   * @param clause such as '=', '<', 'LIKE', etc., plus placeholders ?
   * @param arguments corresponding to the placeholders.
   */
  protected void addClause(String clause, Object... arguments) {
    if (arguments == null || arguments.length == 0) {
      return;
    }

    if (predicate.length() > 0) {
      predicate.append(" AND ");
    }

    predicate.append(clause);

    this.arguments.addAll(asList(arguments));
  }

  /**
   * Constructs a multiple value OR clause, optionally prefixing it with AND.
   *
   * @param clause such as '=', '<', 'LIKE', etc., plus placeholders ?
   * @param arguments corresponding to the placeholders.
   */
  protected void addOrClause(String clause, Object... arguments) {
    if (arguments == null || arguments.length == 0) {
      return;
    }

    if (predicate.length() > 0) {
      predicate.append(" AND ");
    }

    if (arguments.length > 1) {
      predicate.append("(");
    }

    int i = 0;
    for (; i < arguments.length -1; ++i) {
      predicate.append(clause).append(" OR ");
    }

    predicate.append(clause);

    if (arguments.length > 1) {
      predicate.append(")");
    }

    this.arguments.addAll(asList(Arrays.stream(arguments).map(Object::toString).toArray()));
  }
}
