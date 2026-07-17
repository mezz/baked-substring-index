package net.mezzdev.bakedsubstring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BakedSubstringIndexTest {
	@Test
	public void emptyIndexReturnsNoResultsAndNoElements() {
		BakedSubstringIndex<Object> index = BakedSubstringIndex.builder()
				.build();

		assertTrue(index.getSearchResults("a").isEmpty());
		assertTrue(index.getAllElements().isEmpty());
	}

	@Test
	public void emptyQueryReturnsNoResultsAndDoesNotNotifyConsumer() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("alpha", 1)
				.build();
		AtomicBoolean called = new AtomicBoolean(false);

		index.getSearchResults("", results -> called.set(true));

		assertTrue(index.getSearchResults("").isEmpty());
		assertFalse(called.get());
	}

	@Test
	public void containsEverySubstringFromIndexedKeys() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("cacao", 0)
				.put("bookkeeper", 1)
				.build();

		for (String substring : getSubstrings("cacao")) {
			assertTrue(search(index, substring).contains(0));
		}
		for (String substring : getSubstrings("bookkeeper")) {
			assertTrue(search(index, substring).contains(1));
		}
		assertTrue(search(index, "caco").isEmpty());
		assertTrue(search(index, "boke").isEmpty());
	}

	@Test
	public void returnsOneResultWhenTokenOccursMultipleTimesInOneKey() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("banana", 0)
				.build();

		assertEquals(Set.of(0), search(index, "ana"));
	}

	@Test
	public void supportsRepeatedKeysWithDifferentValues() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("cacao", 0)
				.put("cacao", 1)
				.build();

		assertEquals(Set.of(0, 1), search(index, "cao"));
	}

	@Test
	public void supportsOneTwoAndThreeCharacterQueriesExactly() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("alpha", 0)
				.put("beta", 1)
				.put("gamma", 2)
				.build();

		assertEquals(Set.of(0, 1, 2), search(index, "a"));
		assertEquals(Set.of(0), search(index, "lp"));
		assertEquals(Set.of(2), search(index, "amm"));
		assertTrue(search(index, "zz").isEmpty());
		assertTrue(search(index, "lphx").isEmpty());
	}

	@Test
	public void findsLongSubstringsWithThreeCharacterFragmentIntersection() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("red copper ingot", 0)
				.put("blue copper wire", 1)
				.put("shift register", 2)
				.build();

		assertEquals(Set.of(0, 1), search(index, "copper"));
		assertEquals(Set.of(2), search(index, "shift"));
		assertTrue(search(index, "not-present").isEmpty());
	}

	@Test
	public void doesNotReturnCandidatesThatContainQueryFragmentsWithoutFullQuery() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("abcxxbcde", 0)
				.put("abcde", 1)
				.build();

		assertEquals(Set.of(1), search(index, "abcde"));
	}

	@Test
	public void multipleValuesCanMatchOneToken() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("alpha", 0)
				.put("alpine", 1)
				.put("beta", 2)
				.build();

		assertEquals(Set.of(0, 1), search(index, "alp"));
	}

	@Test
	public void sameValueIdentityIndexedUnderMultipleKeysIsReturnedOnce() {
		Object value = new Object();
		BakedSubstringIndex<Object> index = BakedSubstringIndex.<Object>builder()
				.put("alpha", value)
				.put("beta", value)
				.build();

		Collection<Object> results = index.getSearchResults("a");

		assertEquals(1, results.size());
		assertSame(value, results.iterator().next());
	}

	@Test
	public void differentEqualButNotIdenticalValuesAreReturnedSeparately() {
		EqualValue first = new EqualValue("value");
		EqualValue second = new EqualValue("value");
		assertNotSame(first, second);

		BakedSubstringIndex<EqualValue> index = BakedSubstringIndex.<EqualValue>builder()
				.put("alpha", first)
				.put("alphabet", second)
				.build();

		List<EqualValue> results = new ArrayList<>(index.getSearchResults("alph"));

		assertEquals(2, results.size());
		assertTrue(results.stream().anyMatch(value -> value == first));
		assertTrue(results.stream().anyMatch(value -> value == second));
	}

	@Test
	public void getAllElementsReturnsEachValueIdentityOnce() {
		Object shared = new Object();
		EqualValue first = new EqualValue("value");
		EqualValue second = new EqualValue("value");
		BakedSubstringIndex<Object> index = BakedSubstringIndex.builder()
				.put("alpha", shared)
				.put("beta", shared)
				.put("gamma", first)
				.put("delta", second)
				.build();

		Collection<Object> results = index.getAllElements();

		assertEquals(3, results.size());
		assertTrue(containsIdentity(results, shared));
		assertTrue(containsIdentity(results, first));
		assertTrue(containsIdentity(results, second));
	}

	@Test
	public void repeatedGramsInQueryAreHandledOnce() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("aaaaa", 0)
				.put("aaabaa", 1)
				.build();

		assertEquals(Set.of(0), search(index, "aaaa"));
		assertEquals(Set.of(0, 1), search(index, "aaa"));
	}

	@Test
	public void searchesAreCaseSensitive() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("Alpha", 0)
				.put("alpha", 1)
				.build();

		assertEquals(Set.of(0), search(index, "Al"));
		assertEquals(Set.of(1), search(index, "al"));
		assertTrue(search(index, "AL").isEmpty());
	}

	@Test
	public void supportsUnicodeBmpCharactersAsUtf16CodeUnits() {
		BakedSubstringIndex<Integer> index = BakedSubstringIndex.<Integer>builder()
				.put("café-λambda", 0)
				.put("λine", 1)
				.build();

		assertEquals(Set.of(0), search(index, "é-λ"));
		assertEquals(Set.of(0, 1), search(index, "λ"));
		assertTrue(search(index, "λé").isEmpty());
	}

	@SuppressWarnings("DataFlowIssue")
	@Test
	public void rejectsNullArguments() {
		BakedSubstringIndex.Builder<Object> builder = BakedSubstringIndex.builder();

		assertThrows(NullPointerException.class, () -> builder.put(null, new Object()));
		assertThrows(NullPointerException.class, () -> builder.put("alpha", null));

		BakedSubstringIndex<Object> index = builder.put("alpha", new Object())
				.build();

		assertThrows(NullPointerException.class, () -> index.getSearchResults(null));
		assertThrows(NullPointerException.class, () -> index.getSearchResults("a", null));
	}

	@Test
	public void emptyKeysAreIncludedInAllElementsButDoNotMatchSearches() {
		Object value = new Object();
		BakedSubstringIndex<Object> index = BakedSubstringIndex.<Object>builder()
				.put("", value)
				.build();

		assertTrue(index.getSearchResults("a").isEmpty());
		assertEquals(List.of(value), new ArrayList<>(index.getAllElements()));
	}

	private static <T> Set<T> search(BakedSubstringIndex<T> index, String token) {
		Set<T> results = Collections.newSetFromMap(new IdentityHashMap<>());
		index.getSearchResults(token, results::addAll);
		return results;
	}

	private static Set<String> getSubstrings(String value) {
		Set<String> substrings = new HashSet<>();
		for (int length = 1; length <= value.length(); length++) {
			for (int start = 0; start + length <= value.length(); start++) {
				substrings.add(value.substring(start, start + length));
			}
		}
		return substrings;
	}

	private static boolean containsIdentity(Collection<?> values, Object expected) {
		for (Object value : values) {
			if (value == expected) {
				return true;
			}
		}
		return false;
	}

	private record EqualValue(String value) {
	}
}
