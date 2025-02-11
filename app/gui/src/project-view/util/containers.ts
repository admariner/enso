import { assertDefined, assertEqual } from '@/util/assert'
import type { NonEmptyArray } from '@/util/data/array'
import * as iter from 'enso-common/src/utilities/data/iter'

/**
 * Map that supports Object-based keys.
 *
 * Internally keys are converted to strings using the provided {@link keyMapper} function and
 * then compared.
 * @template Key The type of the keys.
 * @template Value The type of the values.
 */
export class MappedKeyMap<Key, Value> {
  /** The inner map that stores the values. */
  private readonly map = new Map<any, [Key, Value]>()

  /**
   * Construct a new map with a custom key mapper.
   * @param keyMapper The function that maps the user-facing keys to internal keys. It can be some
   * sort of hash function or custom to-string converter. The function should return values that
   * are `===`-equal for keys that should be considered equal.
   */
  constructor(private readonly keyMapper: (key: Key) => any) {}

  /** Set the value for the given key. */
  set(key: Key, value: Value): this {
    const innerKey = this.keyMapper(key)
    this.map.set(innerKey, [key, value])
    return this
  }

  /** Get the value for the given key, or `undefined` if it does not exist. */
  get(key: Key): Value | undefined {
    const innerKey = this.keyMapper(key)
    const entry = this.map.get(innerKey)
    return entry ? entry[1] : undefined
  }

  /** Check if the map contains a value for the given key. */
  has(key: Key): boolean {
    const innerKey = this.keyMapper(key)
    return this.map.has(innerKey)
  }

  /** Remove the value for the given key. */
  delete(key: Key): boolean {
    const innerKey = this.keyMapper(key)
    return this.map.delete(innerKey)
  }

  /** Remove all values from the map. */
  clear(): void {
    this.map.clear()
  }

  /** Get the number of values in the map. */
  get size(): number {
    return this.map.size
  }

  /** Iterate over the values in the map. */
  [Symbol.iterator](): IterableIterator<[Key, Value]> {
    return this.map.values()
  }
}

/**
 * Set that uses a provided function to map the values to keys.
 *
 * It is useful e.g. when the values are objects, and we want to use different equality semantics
 * than the default.
 * @template T The type of the values.
 */
export class MappedSet<T extends object> {
  /** The inner set that stores the keys. */
  private readonly set: Map<any, T>

  /**
   * Construct a new set, optionally setting a custom value mapper.
   * @param valueMapper The function that maps the user-facing values to internal keys. It can be
   * some sort of hash function or custom to-string converter. The function should return values
   * that are `===`-equal for keys that should be considered equal.
   */
  constructor(
    private readonly valueMapper: (key: T) => any,
    elements: Iterable<T> = [],
  ) {
    this.set = new Map(iter.map(elements, (elem) => [valueMapper(elem), elem]))
  }

  /** Add the given value to the set. */
  add(value: T): this {
    const innerKey = this.valueMapper(value)
    this.set.set(innerKey, value)
    return this
  }

  /** Check if the set contains the given value. */
  has(value: T): boolean {
    const innerKey = this.valueMapper(value)
    return this.set.has(innerKey)
  }

  /** Remove the given value from the set. */
  delete(value: T): boolean {
    const innerKey = this.valueMapper(value)
    return this.set.delete(innerKey)
  }

  /** Remove all values from the set. */
  clear(): void {
    this.set.clear()
  }

  /** Get the number of values in the set. */
  get size(): number {
    return this.set.size
  }

  /** Iterate over the values in the set. */
  [Symbol.iterator](): IterableIterator<T> {
    return this.set.values()
  }
}

/**
 * Stack that always has at least one element.
 *
 * It is meant to be used with scope-based operations, thus it does not provide direct `push` and `pop` methods.
 */
export class NonEmptyStack<T> {
  /** The "actual" stack of elements. */
  private readonly stack: NonEmptyArray<T>

  /**
   * Construct a new stack with the given initial value.
   *
   * The value will serve as an always-present bottom of the stack.
   */
  constructor(initial: T) {
    this.stack = [initial]
  }

  /** Temporary pushes the given value to the stack and calls the callback. */
  withPushed<R>(value: T, callback: (value: T) => R): { value: T; result?: R } {
    this.stack.push(value)
    let result = undefined
    try {
      result = callback(value)
    } finally {
      const popped = this.stack.pop()
      assertDefined(popped, 'Stack is empty.')
      assertEqual(popped, value, 'Stack is inconsistent: expected to pop the pushed value.')
    }
    return { value, result }
  }

  /** Get the top-most element of the stack. */
  get top(): T {
    const ret = this.stack[this.stack.length - 1]
    assertDefined(ret, 'Stack is empty.')
    return ret
  }

  /** Iterate over the stack values from the top to the bottom. */
  *valuesFromTop(): Iterable<T> {
    for (let i = this.stack.length - 1; i >= 0; i--) {
      const value = this.stack[i]
      // Be defensive against the stack mutation during the iteration.
      if (value != null) {
        yield value
      }
    }
  }
}
