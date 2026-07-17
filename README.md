# Baked Substring Index

`baked-substring-index` is a small Java library for exact substring search over data that is built once and queried many
times. Callers add key/value pairs to a builder, build the index, and then share the built index for read-only lookup.
If the source data changes, build a replacement index from a fresh builder.

```java
BakedSubstringIndex<String> index = BakedSubstringIndex.<String>builder()
        .put("red copper ingot", "item-1")
        .put("blue copper wire", "item-2")
        .build();

Collection<String> results = index.getSearchResults("copper");
```

## Coordinates

```xml
<dependency>
    <groupId>net.mezzdev</groupId>
    <artifactId>baked-substring-index</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## What It Does

The index maps caller-provided Java `String` keys to caller-provided values. A search returns values whose key contains
the query token exactly, using Java `String` substring semantics over UTF-16 code units.

Two lookup styles are available:

```java
Collection<T> results = index.getSearchResults(token);

index.getSearchResults(token, resultsCollection -> {
    // Consume broad result sets without requiring the caller-facing API to promise a specific collection type.
});
```

`getAllElements()` returns all indexed values. Search results and all-elements results are de-duplicated by object
identity (`==`), not `equals`. If the same value object is indexed under multiple keys, it appears once. If two distinct
objects are equal according to `equals`, both can appear.

Result iteration order is intentionally unspecified.

## Examples

For the examples below, assume this index:

```java
BakedSubstringIndex<String> index = BakedSubstringIndex.<String>builder()
        .put("banana", "fruit-0")
        .put("bandana", "cloth-1")
        .put("cabana", "hut-2")
        .build();
```

Returned values are shown as sets because iteration order is unspecified.

| Query token | Returned values | Notes |
| --- | --- | --- |
| `ana` | `{fruit-0, cloth-1, hut-2}` | Found wherever the exact substring appears. |
| `ba` | `{fruit-0, cloth-1, hut-2}` | Short query; uses one exact posting list. |
| `bana` | `{fruit-0, hut-2}` | `banana` starts with it; `cabana` contains it; `bandana` does not. |
| `nda` | `{cloth-1}` | Matches a middle substring in one key. |
| `bandanas` | empty | Longer than any matching substring in the indexed keys. |
| `ANA` | empty | Matching is case-sensitive. |
| empty string | empty | Empty search tokens intentionally return no results. |

Key boundaries are not searchable. If an index contains `"abc" -> "first"` and `"def" -> "second"`, searching for
`cd` returns no results even though `c` ends one key and `d` begins the next key.

## When To Use It

Use this library for:

- Data that is stable while it is being queried, even if the application occasionally rebuilds the index after a batch
  of changes.
- Exact substring search over labels, names, descriptions, identifiers, metadata fields, and other short to medium
  strings.
- Read-heavy workloads where build time and memory are paid upfront.
- Broad partial matching where short one-, two-, and three-character queries are common and should be direct index
  lookups.

Avoid it for:

- Frequent runtime mutation.
- Ranking or relevance scoring.
- Fuzzy search or edit-distance search.
- Locale-aware search.
- Tokenized search or stemming.
- Case folding or Unicode normalization unless callers normalize input before indexing and searching.
- Returning match positions, spans, offsets, or per-occurrence results.
- Workloads where very common short fragments match most keys and consuming very large result sets is unacceptable.

## What A q-Gram Index Is

A q-gram is a fixed-length string fragment. A q-gram index maps each fragment to the records that contain it, then uses
those posting lists to produce candidate records for a query. A posting list is the list of entry ids for keys that
contain a fragment. A candidate is an entry that has the required fragments and may still need full-query verification.

This implementation indexes the unique one-, two-, and three-character fragments in each key. Queries of one to three
UTF-16 code units use the exact fragment posting list. Longer queries collect their unique three-character fragments,
sort posting lists from smallest to largest, intersect them, and verify each remaining candidate with `String.contains`.

This keeps exact substring semantics without storing every possible trailing slice or substring.

The built index stores the original keys, value references, and posting lists from encoded short fragments to key
entries.

Here is what `build()` creates as entries are added. The one-, two-, and three-key snapshots below show separate built
indexes after the first entry, first two entries, and all three entries. The words are chosen to show shared fragments
across keys and repeated fragments within a key:

```java
BakedSubstringIndex.Builder<String> builder = BakedSubstringIndex.builder();
builder.put("banana", "fruit-0");
builder.put("bandana", "cloth-1");
builder.put("cabana", "hut-2");
```

`build()` assigns each key/value pair an entry id, copies keys and values into parallel arrays, and creates a fragment
map from every unique one-, two-, and three-character fragment in each key to the entry ids containing that fragment.

With one key, after `put("banana", "fruit-0")`, the baked structure is:

```text
keys[]
  0 -> "banana"

values[]
  0 -> "fruit-0"

fragment map
  "a"   -> [0]
  "an"  -> [0]
  "ana" -> [0]
  "b"   -> [0]
  "ba"  -> [0]
  "ban" -> [0]
  "n"   -> [0]
  "na"  -> [0]
  "nan" -> [0]
```

Even though `banana` contains `ana` twice, the fragment map stores entry `0` once for `ana`. Repeated fragments inside
one key do not create duplicate postings for that key.

With two keys, after also adding `put("bandana", "cloth-1")`, shared fragments point to both entries and new fragments
from `bandana` point only to entry `1`:

```text
keys[]
  0 -> "banana"
  1 -> "bandana"

values[]
  0 -> "fruit-0"
  1 -> "cloth-1"

fragment map
  "a"   -> [0, 1]
  "an"  -> [0, 1]
  "ana" -> [0, 1]
  "and" -> [1]
  "b"   -> [0, 1]
  "ba"  -> [0, 1]
  "ban" -> [0, 1]
  "d"   -> [1]
  "da"  -> [1]
  "dan" -> [1]
  "n"   -> [0, 1]
  "na"  -> [0, 1]
  "nan" -> [0]
  "nd"  -> [1]
  "nda" -> [1]
```

For the example above with all three keys, the baked structure is:

```text
keys[]
  0 -> "banana"
  1 -> "bandana"
  2 -> "cabana"

values[]
  0 -> "fruit-0"
  1 -> "cloth-1"
  2 -> "hut-2"

fragment map
  "a"   -> [0, 1, 2]
  "ab"  -> [2]
  "aba" -> [2]
  "an"  -> [0, 1, 2]
  "ana" -> [0, 1, 2]
  "and" -> [1]
  "b"   -> [0, 1, 2]
  "ba"  -> [0, 1, 2]
  "ban" -> [0, 1, 2]
  "c"   -> [2]
  "ca"  -> [2]
  "cab" -> [2]
  "d"   -> [1]
  "da"  -> [1]
  "dan" -> [1]
  "n"   -> [0, 1, 2]
  "na"  -> [0, 1, 2]
  "nan" -> [0]
  "nd"  -> [1]
  "nda" -> [1]
```

The numbers in the fragment map are entry ids. A lookup follows those ids back to `keys[]` for exact verification when
needed, and then to `values[]` for returned values.

A short lookup such as `ba` uses the exact short-fragment posting list.

Compactly:

```text
query: "ba"
posting list: [0, 1, 2]
returned values include: {fruit-0, cloth-1, hut-2}
```

In detail:

```text
query: "ba"
fragment map lookup:
  "ba" -> [0, 1, 2]

entry ids -> keys:
  0 -> "banana"
  1 -> "bandana"
  2 -> "cabana"

returned values include:
  "fruit-0"
  "cloth-1"
  "hut-2"
```

A longer lookup such as `bana` uses three-character fragments first, then verifies the full query.

Compactly:

```text
query: "bana"
candidate entry ids: {0, 1, 2}
verified entry ids: {0, 2}
returned values include: {fruit-0, hut-2}
```

In detail:

```text
query: "bana"
unique three-character query fragments:
  "ban"
  "ana"

fragment map lookups:
  "ban" -> [0, 1, 2]
  "ana" -> [0, 1, 2]

intersection:
  [0, 1, 2]

verify with String.contains("bana"):
  entry 0: "banana"  -> yes
  entry 1: "bandana" -> no
  entry 2: "cabana"  -> yes

returned values include:
  "fruit-0"
  "hut-2"
```

The verification step is what prevents `bandana` from being returned for `bana`: it contains both `ban` and `ana`, but
not the full substring `bana`.

For implementation-focused notes on build, lookup, fragment encoding, identity de-duplication, and immutability, see
[docs/implementation.md](docs/implementation.md).

## Why A Builder And Immutable Index

The fragment map is easiest and cheapest to build in batches. During construction, the builder can collect all key/value
pairs, assign stable entry ids, remove repeated fragments within each key, and append entry ids to posting lists in
sorted order. `build()` then freezes those mutable lists into compact arrays for lookup.

Allowing mutation after `build()` would make the data structure more complicated. Adding or removing one key would need
updates across every one-, two-, and three-character fragment in that key, plus careful handling of duplicate value
identities and posting-list ordering. Removal is especially awkward because entry ids are array positions; deleting an
entry would either leave holes or require rewriting ids in many posting lists.

The immutable index keeps lookup simple: searches can read arrays and posting lists without locks, defensive copies, or
concurrent modification checks. Built indexes are therefore safe to share between threads as long as the caller treats
the indexed key strings and value objects themselves appropriately.

## Complexity And Memory

Let:

- `N` be the total indexed input size: the total number of UTF-16 code units across indexed keys, plus one entry slot
  per key/value pair.
- `m` be the query token length.

| Operation | Cost | Comment |
| --- | --- | --- |
| Build index | `O(N)` | Each key contributes a linear number of one-, two-, and three-character fragments, assuming normal hash-map behavior. |
| Built memory | `O(N)` | Stores key/value entries plus posting-list entries for the indexed fragments. |
| Short lookup, `m <= 3` | `O(N)` worst case | Reads one exact gram posting list and streams matching values; selective grams visit much less than `N`. |
| Long lookup, `m > 3` | `O(m^2 + mN)` worst case | Prepares query trigrams, intersects posting lists, then verifies candidate keys. |

Build time is linear under normal hash-map behavior because each key contributes at most
`length(key) + max(0, length(key) - 1) + max(0, length(key) - 2)` indexed fragment occurrences before repeated fragments
inside that key are removed, plus one key/value entry. The memory bound has a larger constant factor than a plain list
of entries because each key can contribute one-, two-, and three-character fragment references.

Empty tokens return immediately. Non-empty queries can return at most one distinct value identity per indexed entry, so
result collection and identity de-duplication are included in the `O(N)` part of the lookup bound.

One-, two-, and three-character queries use exact gram postings. They do not need `String.contains` verification because
the gram is the whole query. Broad grams such as `a` can still be `O(N)` because they may match many keys, but selective
grams only scan their posting list.

Longer queries prepare up to `m - 2` three-character fragments, intersect the indexed entries that contain those
fragments, and verify the remaining keys with `String.contains`. Query preparation is `O(m^2)` in the current
implementation because duplicate checking and posting-list ordering scan the fragments already collected. Filtering and
verification can approach `mN` in the worst case, but they are usually smaller when the query fragments are selective.

## Thread Safety

Built indexes are immutable and safe to share between threads. Builders are mutable and should not be shared without
external synchronization.

## Unicode

Matching uses exact Java `String` substring semantics over UTF-16 code units. The library does not perform Unicode
normalization, locale-aware comparison, case folding, tokenization, or code-point-aware segmentation.

## References

The implementation is an exact, immutable key/value adaptation of q-gram indexing and candidate verification ideas from
the string-matching literature. The references below are mostly about approximate matching, error-tolerant retrieval, or
searching positions in larger text collections. This library deliberately does less: it has no edit-distance threshold,
does not allow missing or substituted characters, does not rank matches, and does not return text positions or blocks.
It indexes whole caller-provided keys, generates candidate keys from fixed-length fragment postings, and then verifies
each candidate with exact `String.contains` before returning the associated values.

- Esko Ukkonen, "Approximate string-matching with q-grams and maximal matches", Theoretical Computer Science 92(1),
  191-211, 1992. DOI: <https://doi.org/10.1016/0304-3975(92)90143-4>.
- Gonzalo Navarro and Ricardo Baeza-Yates, "A Practical q-Gram Index for Text Retrieval Allowing Errors", CLEI
  Electronic Journal 1(2), 1998. DOI: <https://doi.org/10.19153/cleiej.1.2.3>.
- Udi Manber and Sun Wu, "GLIMPSE: A Tool to Search Through Entire File Systems", USENIX Winter 1994 Technical
  Conference, 1994.
  <https://www.usenix.org/conference/usenix-winter-1994-technical-conference/glimpse-tool-search-through-entire-file-systems>.

## Build

```sh
./mvnw test
```
