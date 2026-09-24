package pal

import Twoway.*

class TwowaySuite extends munit.FunSuite {

  test("critical factorization and search on small cases") {
    assertEquals(criticalFactorization("abaab"), (2, 3))
    assertEquals(criticalFactorization("aaaa"), (0, 1))
    assertEquals(criticalFactorization("ba"), (1, 1))
    assertEquals(twoWaySearch("aba", "abaabaaba"), Vector(0, 3, 6))
    assertEquals(twoWaySearch("aa", "aaaa"), Vector(0, 1, 2))
    assertEquals(twoWaySearch("", "abc"), Vector(0, 1, 2, 3))
    assertEquals(twoWaySearch("abcd", "abc"), Vector.empty)
  }

  test("twoWaySearch agrees with brute force exhaustively") {
    for (nl <- 1 to 4; hl <- 0 to 7; needle <- product("ab", nl); hay <- product("ab", hl)) {
      assertEquals(twoWaySearch(needle, hay), bruteOccurrences(needle, hay), s"$needle in $hay")
    }
  }

  test("the (documented as broken) border reduction keeps its known behavior") {
    assertEquals(borders("aba"), Vector.empty)
    assertEquals(bruteBorders("aba"), Vector(1))
    assertEquals(borders("abab"), Vector(2))
    assertEquals(longestBorder(""), 0)
    val counter = new Counter
    borders("aaaa", Some(counter))
    assert(counter.value > 0)
  }

  test("product enumerates in lexicographic order") {
    assertEquals(product("ab", 2).toVector, Vector("aa", "ab", "ba", "bb"))
    assertEquals(product("ab", 0).toVector, Vector(""))
  }
}
