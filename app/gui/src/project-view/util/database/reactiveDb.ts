/**
 * Tools for managing reactive key-value databases.
 *
 * 1. `ReactiveDb`: This is a reactivity-friendly database that serves as a thin adapter around a standard `Map`.
 *    It offers the basic functionality of a `Map` while also emitting events that allow observers to track changes efficiently.
 *
 * 2. `ReactiveIndex`: This is an abstraction, that allows constructing arbitrary database indexes.
 *    Indexes update reactively, can depend on each other, and defer updates until the next lookup for efficiency.
 *
 * 3. `ReactiveMapping`: Similar to `ReactiveIndex`, but each key is correlated to one value (`M`) or zero (`undefined`).
 */

import { assert } from '@/util/assert'
import { LazySyncEffectSet, type NonReactiveView } from '@/util/reactivity'
import * as map from 'lib0/map'
import { ObservableV2 } from 'lib0/observable'
import * as set from 'lib0/set'
import {
  computed,
  effectScope,
  onScopeDispose,
  reactive,
  toRaw,
  type ComputedRef,
  type DebuggerOptions,
} from 'vue'

export type OnDelete = (cleanupFn: () => void) => void

type ReactiveDBNotification<K, V> = {
  entryAdded(key: K, value: V, onDelete: OnDelete): void
}

/**
 * Represents a reactive database adapter that extends the behaviors of `Map`.
 * It emits an `entryAdded` event when a new entry is added to the database,
 * facilitating reactive tracking of database insertions and deletions.
 * @template K - The key type for the database entries.
 * @template V - The value type for the database entries.
 */
export class ReactiveDb<K, V>
  extends ObservableV2<ReactiveDBNotification<K, V>>
  implements Iterable<[K, V]>
{
  _internal: Map<K, V>
  onDelete: Map<K, Set<() => void>>;
  [Symbol.iterator] = this.entries

  /** TODO: Add docs */
  constructor() {
    super()
    this._internal = reactive(map.create())
    this.onDelete = map.create()
  }

  /**
   * Sets a new key-value pair in the database, equivalent to `Map.set`.
   * The function also emits an `entryAdded` event after the addition.
   * @param key - The key for the new entry.
   * @param value - The value for the new entry.
   */
  set(key: K, value: V) {
    // Trigger a reactive update when replacing one entry with another.
    this.delete(key)

    this._internal.set(key, value)
    const reactiveValue = this._internal.get(key) as V
    const onDelete: OnDelete = (callback) => {
      const callbacks = map.setIfUndefined(this.onDelete, key, set.create)
      callbacks.add(callback)
    }
    this.emit('entryAdded', [key, reactiveValue, onDelete])
  }

  /**
   * Retrieves the value corresponding to a specified key in the database, equivalent to `Map.get`.
   * @param key - The key for which to retrieve the value.
   * @returns The value associated with the key, or undefined if the key is not found.
   */
  get(key: K): V | undefined {
    return this._internal.get(key)
  }

  /**
   * Retrieves the value corresponding to a specified key, without creating any reactive dependency for the lookup or
   * any subsequent access to the data.
   *
   * This can be useful to read a reactive value for the purpose of incrementally updating it, without creating a cyclic
   * dependency.
   * @param key - The key for which to retrieve the value.
   * @returns The value associated with the key, or undefined if the key is not found.
   */
  getUntracked(key: K): NonReactiveView<V> | undefined {
    const value = toRaw(this._internal).get(key)
    if (value === undefined) return
    return value as NonReactiveView<V>
  }

  /**
   * Checks if the database contains the key.
   * @param key - A key to chec.
   */
  has(key: K): boolean {
    return this._internal.has(key)
  }

  /**
   * Retrieves the value corresponding to a specified key in the database, or insert the provided
   * one if missing. Will emit an `entryAdded` event after the addition.
   * @param key - The key for which to retrieve the value.
   * @param valueToAdd - Value to add if there's no value under `key`.
   * @returns The value associated with the key. If was missing, the `valueToAdd` is returned.
   */
  getOrAdd(key: K, valueToAdd: () => V): V {
    if (!this._internal.has(key)) {
      this.set(key, valueToAdd())
    }
    const value = this._internal.get(key)
    assert(value != null)
    return value
  }

  /**
   * Deletes the key-value pair identified by the specified key from the database, equivalent to `Map.delete`.
   * @param key - The key for which to delete the corresponding key-value pair.
   * @returns True if the deletion was successful, or false if the key was not found.
   */
  delete(key: K): boolean {
    this.onDelete.get(key)?.forEach((callback) => callback())
    this.onDelete.delete(key)
    return this._internal.delete(key)
  }

  /**
   * Retrieves the number of key-value pairs currently in the database, equivalent to `Map.size`.
   * @returns The number of key-value pairs in the database.
   */
  get size(): number {
    return this._internal.size
  }

  /**
   * Retrieves an iterator over entries in the database, equivalent to `Map.entries`.
   * @returns An iterator that yields key-value pairs in the database.
   */
  entries(): IterableIterator<[K, V]> {
    return this._internal.entries()
  }

  /**
   * Retrieves an iterator over keys in the database, equivalent to `Map.keys`.
   * @returns An iterator that yields keys in the database.
   */
  keys(): IterableIterator<K> {
    return this._internal.keys()
  }

  /**
   * Retrieves an iterator over values in the database, equivalent to `Map.values`.
   * @returns An iterator that yields values in the database.
   */
  values(): IterableIterator<V> {
    return this._internal.values()
  }

  /**
   * Moves an entry to the bottom of the database, making it the last entry in the iteration order
   * of `entries()`.
   */
  moveToLast(id: K) {
    const value = this._internal.get(id)
    if (value !== undefined) {
      this._internal.delete(id)
      this._internal.set(id, value)
    }
  }
}

/**
 * A function type representing an indexer for a `ReactiveIndex`.
 *
 * An `Indexer` takes a key-value pair from the `ReactiveDb` and produces an array of index key-value pairs,
 * defining how the input key and value maps to keys and values in the index.
 * @param key - The key from the `ReactiveDb`.
 * @param value - The value from the `ReactiveDb`.
 * @returns An Array representing multiple key-value pair(s) ([IK, IV]) for the index.
 */
export type Indexer<K, V, IK, IV> = (key: K, value: V) => [IK, IV][]

/**
 * Provides automatic indexing for a `ReactiveDb` instance.
 * Utilizes both forward and reverse mapping for efficient lookups and reverse lookups.
 * The index updates not only after key/value change from db, but also on any change of
 * reactive dependency.
 * @template K - The key type of the ReactiveDb.
 * @template V - The value type of the ReactiveDb.
 * @template IK - The key type of the index.
 * @template IV - The value type of the index.
 */
export class ReactiveIndex<K, V, IK, IV> {
  /** Forward map from index keys to a set of index values */
  forward: Map<IK, Set<IV>>
  /** Reverse map from index values to a set of index keys */
  reverse: Map<IV, Set<IK>>
  /** Collections of effects to sync data between ReactiveDB and ReactiveIndex */
  effects: LazySyncEffectSet

  /**
   * Constructs a new ReactiveIndex for the given ReactiveDb and an indexer function.
   * @param db - The ReactiveDb to index.
   * @param indexer - The indexer function defining how db keys and values map to index keys and values.
   */
  constructor(db: ReactiveDb<K, V>, indexer: Indexer<K, V, IK, IV>) {
    this.forward = reactive(map.create())
    this.reverse = reactive(map.create())
    const scope = effectScope()
    this.effects = new LazySyncEffectSet(scope)
    scope.run(() => {
      const handler = db.on('entryAdded', (key, value, onDelete) => {
        const stopEffect = this.effects.lazyEffect((onCleanup) => {
          const keyValues = indexer(key, value)
          keyValues.forEach(([key, value]) => this.writeToIndex(key, value))
          onCleanup(() => keyValues.forEach(([key, value]) => this.removeFromIndex(key, value)))
        })
        onDelete(() => stopEffect())
      })
      onScopeDispose(() => db.off('entryAdded', handler))
    })
  }

  /**
   * Adds a new key-value pair to the forward and reverse index.
   * @param key - The key to add to the index.
   * @param value - The value to associate with the key.
   */
  writeToIndex(key: IK, value: IV): void {
    const forward = map.setIfUndefined(this.forward, key, set.create)
    if (forward.has(value)) {
      console.error(
        `Attempt to repeatedly write the same key-value pair (${[
          key,
          value,
        ]}) to the index. Please check your indexer implementation.`,
      )
    } else {
      forward.add(value)
      const reverse = map.setIfUndefined(this.reverse, value, set.create)
      reverse.add(key)
    }
  }

  /**
   * Removes a key-value pair from the forward and reverse index.
   * @param key - The key to remove from the index.
   * @param value - The value associated with the key.
   */
  removeFromIndex(key: IK, value: IV): void {
    const remove = <K, V>(map: Map<K, Set<V>>, key: K, value: V) => {
      // Removing reactivity from `map` is extremely important to avoid spurious reactive updates.
      // The bug was introduced because `values.size` access in `onCleanup` callback registered `onCleanup`
      // to be called again after any change in `values`, causing and infinite loop.
      // We have a dedicated regression test for the possible issues related to this method.
      // (`Indexing does not cause spurious reactive updates`)
      const rawMap = toRaw(map)
      const values = rawMap.get(key)
      values?.delete(value)
      if (values?.size === 0) {
        // Important: deleting a key must be visible for dependent reactive values,
        // so we use `map` here instead of `rawMap`.
        map.delete(key)
      }
    }
    remove(this.forward, key, value)
    remove(this.reverse, value, key)
  }

  /** TODO: Add docs */
  allForward(): IterableIterator<[IK, Set<IV>]> {
    this.effects.flush()
    return this.forward.entries()
  }

  /** TODO: Add docs */
  allReverse(): IterableIterator<[IV, Set<IK>]> {
    this.effects.flush()
    return this.reverse.entries()
  }

  /**
   * Look for key in the forward index.
   * Returns a set of values associated with the given index key.
   * @param key - The index key to look up values for.
   * @returns A set of values corresponding to the key or an empty set if the key is not present in the index.
   */
  lookup(key: IK): Set<IV> {
    this.effects.flush()
    return this.forward.get(key) ?? set.create()
  }

  /**
   * Returns a set of keys associated with the given index value.
   * @param value - The index value to reverse look up keys for.
   * @returns A set of keys corresponding to the value or an empty set if the value is not present in the index.
   */
  reverseLookup(value: IV): Set<IK> {
    this.effects.flush()
    return this.reverse.get(value) ?? set.create()
  }

  /** TODO: Add docs */
  hasKey(key: IK): boolean {
    return this.forward.has(key)
  }

  /** TODO: Add docs */
  hasValue(value: IV): boolean {
    return this.reverse.has(value)
  }
}

/**
 * A function type representing a mapper function for {@link ReactiveMapping}.
 *
 * It takes a key-value pair from the {@link ReactiveDb} and produces a mapped value, which is then stored
 * and can be looked up by the key.
 * @param key - The key from the {@link ReactiveDb}.
 * @param value - The value from the {@link ReactiveDb}.
 * @returns A result of a mapping to store in the {@link ReactiveMapping}.
 */
export type Mapper<K, V, IV> = (key: K, value: V) => IV | undefined

/**
 * A one-to-one mapping for values in a {@link ReactiveDb} instance. Allows only one value per key.
 * It can be thought of as a collection of `computed` values per each key in the `ReactiveDb`. The
 * mapping is automatically updated when any of its dependencies change, and is properly cleaned up
 * when any key is removed from {@link ReactiveDb}. Only accessed keys are ever actually computed.
 * @template K - The key type of the ReactiveDb.
 * @template V - The value type of the ReactiveDb.
 * @template M - The type of a mapped value.
 */
export class ReactiveMapping<K, V, M> {
  /** Forward map from index keys to a mapped computed value */
  computed: Map<K, ComputedRef<M | undefined>>

  /**
   * Constructs a new {@link ReactiveMapping} for the given {@link ReactiveDb} and an mapper function.
   * @param db - The ReactiveDb to map over.
   * @param indexer - The indexer function defining how db keys and values are mapped.
   */
  constructor(db: ReactiveDb<K, V>, indexer: Mapper<K, V, M>, debugOptions?: DebuggerOptions) {
    this.computed = reactive(map.create())
    const scope = effectScope()
    scope.run(() => {
      const handler = db.on('entryAdded', (key, value, onDelete) => {
        const scope = effectScope()
        const mappedValue = scope.run(() =>
          computed(() => scope.run(() => indexer(key, value)), debugOptions),
        )! // This non-null assertion is SAFE, as the scope is initially active.
        this.computed.set(key, mappedValue)
        onDelete(() => {
          scope.stop()
          this.computed.delete(key)
        })
      })
      onScopeDispose(() => db.off('entryAdded', handler))
    })
  }

  /**
   * Look for key in the mapping.
   * Returns a mapped value associated with given key.
   * @param key - The index key to look up values for.
   * @returns A mapped value, if the key is present in the mapping.
   */
  lookup(key: K): M | undefined {
    return this.computed.get(key)?.value
  }
}
