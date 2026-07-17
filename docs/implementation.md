# Implementation Notes

This document describes the internal shape of `BakedSubstringIndex`. The public contract is still the README and
Javadocs; the details here are meant to explain the current implementation.

## Build Flow

`Builder.put(key, value)` only records the key and value. The expensive indexing work happens in `build()`.

During `build()`, each key/value pair gets an entry id from its position in the builder lists. The builder then extracts
all one-, two-, and three-character fragments from each key. Repeated fragments within the same key are ignored, so one
key contributes at most one posting to a given fragment.

The posting lists are naturally sorted by entry id because entries are processed in insertion order and each entry is
appended at most once per fragment.

## Fragment Encoding

Fragments are encoded into a `long` so they can be used as primitive map keys. The encoded value stores the fragment
length and up to three UTF-16 code units.

```text
fragment "ba"

length: 2
chars:  b, a

encoded long:
  length in high bits
  b in first char slot
  a in second char slot
```

The encoding is an implementation detail. It preserves exact Java `String` / UTF-16 code-unit matching and does not
normalize text.

## Short Query Flow

Queries with length one, two, or three use one exact fragment lookup. Candidate keys do not need full-query verification
because the fragment is the whole query.

For the README example, `ba` uses the posting list for `ba`:

```text
"ba" -> [0, 1, 2]

0 -> "banana"  -> "fruit-0"
1 -> "bandana" -> "cloth-1"
2 -> "cabana"  -> "hut-2"
```

## Long Query Flow

Queries longer than three characters use the query's unique three-character fragments. If any fragment is absent from the
index, the query cannot match any key. Otherwise, the implementation sorts the fragment posting lists from shortest to
longest, intersects them, and verifies each remaining key with `String.contains`.

For the README example, `bana` uses `ban` and `ana`:

```text
"ban" -> [0, 1, 2]
"ana" -> [0, 1, 2]

intersection -> [0, 1, 2]

String.contains("bana")
  "banana"  -> yes
  "bandana" -> no
  "cabana"  -> yes
```

The full-query verification step is required because sharing all three-character fragments does not guarantee that the
full query appears contiguously in the key.

## Identity De-duplication

The index de-duplicates values by object identity. If the same value object is indexed under multiple matching keys, a
lookup should return that object once. Equal but distinct value objects remain distinct.

To avoid paying this cost for the common case, `build()` records whether any value identity appears more than once.
When all value identities are unique, search results can use a lightweight candidate collection. When duplicate
identities exist, matching values are copied through an identity set before being returned.

## Immutability

The built index is immutable because its arrays and posting lists rely on stable entry ids. Adding or removing a key
after build would require updating every fragment for that key and preserving posting-list ordering. Removing entries
would also require either holes in the arrays or rewriting entry ids throughout the fragment map.

The immutable shape keeps lookup simple: searches read arrays and posting lists without locks, defensive copies, or
concurrent modification checks.
