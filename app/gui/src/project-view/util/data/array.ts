/** @file Functions for querying and manipulating arrays. */

import type { Opt } from '@/util/data/opt'

/** An array that has at least one element present at all times. */
export type NonEmptyArray<T> = [T, ...T[]]

/** An equivalent of `Array.prototype.findIndex` method, but returns null instead of -1. */
export function findIndexOpt<T>(
  arr: T[],
  pred: (elem: T, index: number) => boolean,
): number | null {
  const index = arr.findIndex(pred)
  return index >= 0 ? index : null
}

/**
 * Returns the index of the partition point according to the given predicate
 * (the index of the first element of the second partition).
 *
 * The array is assumed to be partitioned according to the given predicate.
 * This means that all elements for which the predicate returns `true` are at the start of the array
 * and all elements for which the predicate returns `false` are at the end.
 * For example, `[7, 15, 3, 5, 4, 12, 6]` is partitioned under the predicate `x % 2 != 0`
 * (all odd numbers are at the start, all even at the end).
 *
 * If this array is not partitioned, the returned result is unspecified and meaningless,
 * as this method performs a kind of binary search.
 * @see The original docs for the equivalent function in Rust: {@link https://doc.rust-lang.org/std/primitive.slice.html#method.partition_point}
 */
export function partitionPoint<T>(
  array: T[],
  pred: (elem: T) => boolean,
  start = 0,
  end = array.length,
): number {
  while (start < end) {
    // Shift right by one to halve and round down in the same step.
    const middle = (start + end) >> 1
    if (pred(array[middle]!)) start = middle + 1
    else end = middle
  }
  return start
}

/** Index into an array using specified index. When the index is nullable, returns undefined. */
export function tryGetIndex<T>(arr: Opt<readonly T[]>, index: Opt<number>): T | undefined {
  return index == null ? undefined : arr?.[index]
}

/**
 * Check if two byte arrays have the same value. Allows optional values. If both inputs are `null`
 * or `undefined`, the comparison also succeeds.
 */
export function byteArraysEqual(a: Opt<Uint8Array>, b: Opt<Uint8Array>): boolean {
  return a === b || (a != null && b != null && indexedDB.cmp(a, b) === 0)
}

/** TODO: Add docs */
export function arrayEquals<T>(a: T[], b: T[]): boolean {
  return a === b || (a.length === b.length && a.every((v, i) => v === b[i]))
}

/**
 * Return the rightmost index of an array element that passes the predicate. Returns `undefined` if
 * no such element has been found.
 */
export function findLastIndex<T>(array: T[], pred: (elem: T) => boolean): number | undefined {
  for (let i = array.length - 1; i >= 0; --i) {
    if (pred(array[i]!)) return i
  }
}

/**
 * Split iterable into two arrays based on predicate.
 *
 * The predicate passed to `partition` can return true, or false. `partition` returns a pair, all of
 * the elements for which it returned true, and all of the elements for which it returned false.
 */
export function partition<T>(array: Iterable<T>, pred: (elem: T) => boolean): [T[], T[]] {
  const truthy: T[] = []
  const falsy: T[] = []

  for (const element of array) {
    const target = pred(element) ? truthy : falsy
    target.push(element)
  }

  return [truthy, falsy]
}

/**
 * Find smallest index at which two arrays differ. Returns an index past the array (i.e. array length) when both arrays
 * are equal. Note that the default comparator uses strict equality, and so `NaN` values will be considered different.
 */
export function findDifferenceIndex<T>(
  lhs: T[],
  rhs: T[],
  equals = (a: T, b: T) => a === b,
): number {
  return (
    findIndexOpt(lhs, (item, index) => index >= rhs.length || !equals(item, rhs[index]!)) ??
    lhs.length
  )
}
