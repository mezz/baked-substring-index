package net.mezzdev.bakedsubstring;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Immutable substring index for data that is built once and queried many times.
 * <p>
 * The built index cannot be mutated. If the source data changes, create a new builder and build a replacement index.
 * This makes the index a good fit for data that is stable during lookup, even if the application occasionally rebuilds
 * it after a batch of changes.
 * <p>
 * The index stores each key by its unique short character fragments. Searches for tokens up to three UTF-16 code units
 * use an exact fragment lookup. Longer searches intersect unique three-character query fragments, shortest posting list
 * first, and then verify candidates with {@link String#contains(CharSequence)} to preserve exact substring semantics.
 * <p>
 * Values are de-duplicated by identity, not by {@link Object#equals(Object)}. If the same value object is indexed under
 * multiple matching keys, a search result includes it once. Equal but distinct value objects are returned separately.
 * <p>
 * This implementation is an exact, immutable key/value adaptation of q-gram indexing and candidate verification ideas
 * from the string-matching literature. Unlike the approximate matching papers below, it does not implement edit-distance
 * thresholds, error-tolerant matching, ranking, or position/block retrieval; it verifies candidate keys with exact
 * {@link String#contains(CharSequence)} before returning their associated values. Relevant background includes:
 * <ul>
 * <li>Esko Ukkonen, <a href="https://doi.org/10.1016/0304-3975(92)90143-4">Approximate string-matching with q-grams
 * and maximal matches</a>, Theoretical Computer Science 92(1), 191-211, 1992.</li>
 * <li>Gonzalo Navarro and Ricardo Baeza-Yates,
 * <a href="https://doi.org/10.19153/cleiej.1.2.3">A Practical q-Gram Index for Text Retrieval Allowing Errors</a>,
 * CLEI Electronic Journal 1(2), 1998.</li>
 * <li>Udi Manber and Sun Wu,
 * <a href="https://www.usenix.org/conference/usenix-winter-1994-technical-conference/glimpse-tool-search-through-entire-file-systems">
 * GLIMPSE: A Tool to Search Through Entire File Systems</a>, USENIX Winter 1994 Technical Conference, 1994.</li>
 * </ul>
 *
 * @param <T> the value type stored in the index
 */
public final class BakedSubstringIndex<T> {
	private static final int[] NO_ENTRIES = new int[0];

	private final String[] keys;
	private final Object[] values;
	private final Long2ObjectOpenHashMap<int[]> entriesByGram;
	private final boolean deduplicateResults;

	private BakedSubstringIndex(
			String[] keys,
			Object[] values,
			Long2ObjectOpenHashMap<int[]> entriesByGram,
			boolean deduplicateResults
	) {
		this.keys = keys;
		this.values = values;
		this.entriesByGram = entriesByGram;
		this.deduplicateResults = deduplicateResults;
	}

	/**
	 * Creates a builder for a baked substring index.
	 *
	 * @param <T> the value type stored in the index
	 * @return a new builder
	 */
	public static <T> Builder<T> builder() {
		return new Builder<>(null);
	}

	/**
	 * Creates a builder that can index batches of keys while the caller adds more entries.
	 * Only immutable key strings are passed to the executor; values remain on the calling thread.
	 * The caller owns the executor and must keep it available until building is complete.
	 * Rejected work is performed on the calling thread. Builders still require external synchronization.
	 *
	 * @param executor the executor used for indexing key batches
	 * @param <T> the value type stored in the index
	 * @return a new builder
	 * @throws NullPointerException if {@code executor} is null
	 */
	public static <T> Builder<T> builder(Executor executor) {
		return new Builder<>(Objects.requireNonNull(executor, "executor"));
	}

	/**
	 * Finds all values whose indexed key contains {@code token}.
	 * <p>
	 * The consumer is called once when results are present, and is not called when the token has no matches. Empty query
	 * tokens produce no results.
	 *
	 * @param token           the substring to search for
	 * @param resultsConsumer the consumer that receives a result collection
	 * @throws NullPointerException if {@code token} or {@code resultsConsumer} is null
	 */
	public void getSearchResults(String token, Consumer<Collection<T>> resultsConsumer) {
		Objects.requireNonNull(resultsConsumer, "resultsConsumer");
		Collection<T> results = getSearchResults(token);
		if (!results.isEmpty()) {
			resultsConsumer.accept(results);
		}
	}

	/**
	 * Finds all values whose indexed key contains {@code token}.
	 * <p>
	 * Empty query tokens produce an empty collection.
	 *
	 * @param token the substring to search for
	 * @return an immutable collection of matching values
	 * @throws NullPointerException if {@code token} is null
	 */
	public Collection<T> getSearchResults(String token) {
		Objects.requireNonNull(token, "token");
		if (token.isEmpty()) {
			return Collections.emptyList();
		}

		int[] candidateEntries = candidateEntries(token);
		if (candidateEntries == null || candidateEntries.length == 0) {
			return Collections.emptyList();
		}

		boolean verifyCandidates = token.length() > 3;
		if (deduplicateResults) {
			return deduplicatedResults(token, candidateEntries, verifyCandidates);
		}

		CandidateResultCollection results = new CandidateResultCollection(token, candidateEntries, verifyCandidates);
		if (results.isEmpty()) {
			return Collections.emptyList();
		}
		return results;
	}

	/**
	 * Returns all unique value identities stored in the index.
	 *
	 * @return an immutable collection containing every indexed value identity once
	 */
	public Collection<T> getAllElements() {
		if (values.length == 0) {
			return Collections.emptyList();
		}
		if (!deduplicateResults) {
			List<T> results = new ArrayList<>(values.length);
			for (Object value : values) {
				results.add(value(value));
			}
			return Collections.unmodifiableList(results);
		}

		Set<T> results = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Object value : values) {
			results.add(value(value));
		}
		return Collections.unmodifiableSet(results);
	}

	private Collection<T> deduplicatedResults(String token, int[] candidateEntries, boolean verifyCandidates) {
		Set<T> results = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int entryIndex : candidateEntries) {
			if (!verifyCandidates || keys[entryIndex].contains(token)) {
				results.add(valueAt(entryIndex));
			}
		}
		if (results.isEmpty()) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableSet(results);
	}

	@Nullable
	private int[] candidateEntries(String token) {
		int tokenLength = token.length();
		if (tokenLength <= 3) {
			return entriesByGram.get(encodeGram(token, 0, tokenLength));
		}

		int maxGramCount = tokenLength - 2;
		long[] grams = new long[maxGramCount];
		int[][] postings = new int[maxGramCount][];
		int gramCount = 0;
		for (int i = 0; i <= tokenLength - 3; i++) {
			long gram = encodeGram(token, i, 3);
			if (contains(grams, gramCount, gram)) {
				continue;
			}

			int[] entries = entriesByGram.get(gram);
			if (entries == null) {
				return null;
			}
			int insertAt = gramCount;
			while (insertAt > 0 && postings[insertAt - 1].length > entries.length) {
				grams[insertAt] = grams[insertAt - 1];
				postings[insertAt] = postings[insertAt - 1];
				insertAt--;
			}
			grams[insertAt] = gram;
			postings[insertAt] = entries;
			gramCount++;
		}
		if (gramCount == 0) {
			return NO_ENTRIES;
		}
		if (gramCount == 1) {
			return postings[0];
		}

		return intersectPostings(postings, gramCount);
	}

	private T valueAt(int index) {
		return value(values[index]);
	}

	@SuppressWarnings("unchecked")
	private static <T> T value(Object value) {
		return (T) value;
	}

	private final class CandidateResultCollection extends AbstractCollection<T> {
		private final String token;
		private final int[] candidateEntries;
		private final boolean verifyCandidates;
		private volatile int size = -1;

		private CandidateResultCollection(String token, int[] candidateEntries, boolean verifyCandidates) {
			this.token = token;
			this.candidateEntries = candidateEntries;
			this.verifyCandidates = verifyCandidates;
		}

		@Override
		public Iterator<T> iterator() {
			return new Iterator<>() {
				private int nextCandidateIndex = findNextCandidate(0);

				@Override
				public boolean hasNext() {
					return nextCandidateIndex >= 0;
				}

				@Override
				public T next() {
					if (nextCandidateIndex < 0) {
						throw new NoSuchElementException();
					}
					int entryIndex = candidateEntries[nextCandidateIndex];
					nextCandidateIndex = findNextCandidate(nextCandidateIndex + 1);
					return valueAt(entryIndex);
				}
			};
		}

		@Override
		public int size() {
			if (size < 0) {
				int count = 0;
				for (int entryIndex : candidateEntries) {
					if (matches(entryIndex)) {
						count++;
					}
				}
				size = count;
			}
			return size;
		}

		private int findNextCandidate(int startIndex) {
			for (int i = startIndex; i < candidateEntries.length; i++) {
				if (matches(candidateEntries[i])) {
					return i;
				}
			}
			return -1;
		}

		private boolean matches(int entryIndex) {
			return !verifyCandidates || keys[entryIndex].contains(token);
		}
	}

	/**
	 * Builder for {@link BakedSubstringIndex}.
	 *
	 * @param <T> the value type stored in the index
	 */
	public static final class Builder<T> {
		private final List<String> keys = new ArrayList<>();
		private static final int BATCH_SIZE = 1024;
		private final List<T> values = new ArrayList<>();
		private final @Nullable Executor executor;
		private final List<FutureTask<Long2ObjectOpenHashMap<IntArrayList>>> batches = new ArrayList<>();
		private int submittedKeys;

		private Builder(@Nullable Executor executor) {
			this.executor = executor;
		}

		/**
		 * Adds a key/value pair to the index being built.
		 * <p>
		 * Empty keys are allowed but cannot match any non-empty search token. Null keys and values are rejected.
		 *
		 * @param key   the string to search
		 * @param value the value to return when {@code key} matches
		 * @return this builder
		 * @throws NullPointerException if {@code key} or {@code value} is null
		 */
		public Builder<T> put(String key, T value) {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(value, "value");
			keys.add(key);
			values.add(value);
			if (executor != null && keys.size() - submittedKeys >= BATCH_SIZE) {
				submitBatch(executor);
			}
			return this;
		}

		/**
		 * Builds an immutable substring index from the current builder contents.
		 *
		 * @return the baked index
		 */
		public BakedSubstringIndex<T> build() {
			String[] keyArray = keys.toArray(String[]::new);
			Object[] valueArray = values.toArray();
			Long2ObjectOpenHashMap<int[]> entriesByGram;
			if (executor == null) {
				entriesByGram = freezeGrams(List.of(buildGrams(keyArray, 0)));
			} else {
				if (submittedKeys < keys.size()) {
					submitBatch(executor);
				}
				List<Long2ObjectOpenHashMap<IntArrayList>> completed = new ArrayList<>(batches.size());
				boolean interrupted = false;
				try {
					for (FutureTask<Long2ObjectOpenHashMap<IntArrayList>> batch : batches) {
						// Help queued work, including when build is called on the same single-thread executor.
						batch.run();
						while (true) {
							try {
								completed.add(batch.get());
								break;
							} catch (InterruptedException e) {
								interrupted = true;
							} catch (ExecutionException e) {
								throw new IllegalStateException("Failed to index key batch", e.getCause());
							}
						}
					}
				} finally {
					if (interrupted) {
						Thread.currentThread().interrupt();
					}
				}
				entriesByGram = freezeGrams(completed);
			}
			Set<T> seenValues = Collections.newSetFromMap(new IdentityHashMap<>());
			boolean deduplicateResults = false;
			for (T value : values) {
				if (!seenValues.add(value)) {
					deduplicateResults = true;
				}
			}
			return new BakedSubstringIndex<>(keyArray, valueArray, entriesByGram, deduplicateResults);
		}

		private void submitBatch(Executor executor) {
			int firstEntry = submittedKeys;
			String[] batchKeys = keys.subList(firstEntry, keys.size()).toArray(String[]::new);
			FutureTask<Long2ObjectOpenHashMap<IntArrayList>> batch =
					new FutureTask<>(() -> buildGrams(batchKeys, firstEntry));
			batches.add(batch);
			submittedKeys = keys.size();
			try {
				executor.execute(batch);
			} catch (RejectedExecutionException e) {
				batch.run();
			}
		}

		private static Long2ObjectOpenHashMap<IntArrayList> buildGrams(String[] keys, int firstEntry) {
			Long2ObjectOpenHashMap<IntArrayList> entries = new Long2ObjectOpenHashMap<>();
			Long2IntOpenHashMap lastEntry = new Long2IntOpenHashMap();
			lastEntry.defaultReturnValue(-1);
			for (int i = 0; i < keys.length; i++) {
				addGrams(entries, lastEntry, keys[i], firstEntry + i);
			}
			return entries;
		}

		private static Long2ObjectOpenHashMap<int[]> freezeGrams(List<Long2ObjectOpenHashMap<IntArrayList>> batches) {
			if (batches.size() == 1) {
				Long2ObjectOpenHashMap<IntArrayList> batch = batches.get(0);
				Long2ObjectOpenHashMap<int[]> entries = new Long2ObjectOpenHashMap<>(batch.size());
				for (Long2ObjectMap.Entry<IntArrayList> entry : batch.long2ObjectEntrySet()) {
					entries.put(entry.getLongKey(), entry.getValue().toIntArray());
				}
				return entries;
			}
			Long2IntOpenHashMap lengths = new Long2IntOpenHashMap();
			for (Long2ObjectOpenHashMap<IntArrayList> batch : batches) {
				for (Long2ObjectMap.Entry<IntArrayList> entry : batch.long2ObjectEntrySet()) {
					lengths.addTo(entry.getLongKey(), entry.getValue().size());
				}
			}
			Long2ObjectOpenHashMap<int[]> entries = new Long2ObjectOpenHashMap<>(lengths.size());
			for (Long2IntMap.Entry entry : lengths.long2IntEntrySet()) {
				entries.put(entry.getLongKey(), new int[entry.getIntValue()]);
			}
			lengths.clear();
			for (Long2ObjectOpenHashMap<IntArrayList> batch : batches) {
				for (Long2ObjectMap.Entry<IntArrayList> entry : batch.long2ObjectEntrySet()) {
					long gram = entry.getLongKey();
					IntArrayList list = entry.getValue();
					int offset = lengths.addTo(gram, list.size());
					list.getElements(0, entries.get(gram), offset, list.size());
				}
			}
			return entries;
		}

		private static void addGrams(
				Long2ObjectOpenHashMap<IntArrayList> entriesByGram,
				Long2IntOpenHashMap lastEntryByGram,
				String key,
				int entryIndex
		) {
			int keyLength = key.length();
			for (int gramLength = 1; gramLength <= 3 && gramLength <= keyLength; gramLength++) {
				for (int i = 0; i <= keyLength - gramLength; i++) {
					long gram = encodeGram(key, i, gramLength);
					if (lastEntryByGram.get(gram) == entryIndex) {
						continue;
					}
					IntArrayList entries = entriesByGram.get(gram);
					if (entries == null) {
						entries = new IntArrayList();
						entriesByGram.put(gram, entries);
					}
					entries.add(entryIndex);
					lastEntryByGram.put(gram, entryIndex);
				}
			}
		}
	}

	private static long encodeGram(String string, int offset, int length) {
		long gram = (long) length << 48;
		for (int i = 0; i < length; i++) {
			gram |= (long) string.charAt(offset + i) << ((2 - i) * 16);
		}
		return gram;
	}

	private static boolean contains(long[] values, int length, long value) {
		for (int i = 0; i < length; i++) {
			if (values[i] == value) {
				return true;
			}
		}
		return false;
	}

	private static int[] intersectPostings(int[][] postings, int postingCount) {
		int[] current = postings[0];
		int currentLength = current.length;
		for (int i = 1; i < postingCount; i++) {
			int[] next = postings[i];
			int[] output = new int[Math.min(currentLength, next.length)];
			currentLength = intersect(current, currentLength, next, output);
			if (currentLength == 0) {
				return NO_ENTRIES;
			}
			current = output;
		}
		if (currentLength == current.length) {
			return current;
		}

		int[] output = new int[currentLength];
		System.arraycopy(current, 0, output, 0, currentLength);
		return output;
	}

	private static int intersect(int[] left, int leftLength, int[] right, int[] output) {
		int leftIndex = 0;
		int rightIndex = 0;
		int outputIndex = 0;
		while (leftIndex < leftLength && rightIndex < right.length) {
			int leftValue = left[leftIndex];
			int rightValue = right[rightIndex];
			if (leftValue == rightValue) {
				output[outputIndex++] = leftValue;
				leftIndex++;
				rightIndex++;
			} else if (leftValue < rightValue) {
				leftIndex++;
			} else {
				rightIndex++;
			}
		}
		return outputIndex;
	}
}
