import { mockProjectNameStore } from '@/stores/projectNames'
import { SuggestionDb, type Group } from '@/stores/suggestionDatabase'
import { SuggestionKind, type SuggestionEntry } from '@/stores/suggestionDatabase/entry'
import { SuggestionUpdateProcessor } from '@/stores/suggestionDatabase/lsUpdate'
import { assert, assertDefined } from '@/util/assert'
import { unwrap } from '@/util/data/result'
import { parseDocs } from '@/util/docParser'
import { parseAbsoluteProjectPath, ProjectPath } from '@/util/projectPath'
import {
  tryIdentifier,
  tryQualifiedName,
  type Identifier,
  type QualifiedName,
} from '@/util/qualifiedName'
import { expect, test } from 'vitest'
import * as lsTypes from 'ydoc-shared/languageServerTypes/suggestions'
import { type SuggestionsDatabaseUpdate } from 'ydoc-shared/languageServerTypes/suggestions'

function stdPath(path: string) {
  assert(path.startsWith('Standard.'))
  return parseAbsoluteProjectPath(unwrap(tryQualifiedName(path)))
}

const projectNames = mockProjectNameStore()

function applyUpdates(
  db: SuggestionDb,
  updates: SuggestionsDatabaseUpdate[],
  { groups }: { groups: Group[] },
) {
  new SuggestionUpdateProcessor(groups, projectNames).applyUpdates(db, updates)
}

test('Entry qualified names', () => {
  const test = new Fixture()
  const db = test.createDbWithExpected()
  const entryQn = (id: number) => projectNames.printProjectPath(db.get(id)!.definitionPath)
  expect(entryQn(1)).toStrictEqual('Standard.Base')
  expect(entryQn(2)).toStrictEqual('Standard.Base.Type')
  expect(entryQn(3)).toStrictEqual('Standard.Base.Type.Con')
  expect(entryQn(4)).toStrictEqual('Standard.Base.Type.method')
  expect(entryQn(5)).toStrictEqual('Standard.Base.Type.static_method')
  expect(entryQn(6)).toStrictEqual('Standard.Base.function')
  expect(entryQn(7)).toStrictEqual('Standard.Base.local')
  expect(entryQn(8)).toStrictEqual('local.Mock_Project.collapsed')
})

test('Project path indexing', () => {
  const test = new Fixture()
  const db = new SuggestionDb()
  const addUpdates = test.addUpdatesForExpected()
  applyUpdates(db, addUpdates, test.suggestionContext)
  for (const { id } of addUpdates) {
    const entry = db.get(id)
    expect(entry).toBeDefined()
    const projectPath = entry?.definitionPath
    assertDefined(projectPath)
    expect(db.findByProjectPath(projectPath)).toEqual(id)
  }
})

test('Parent-children indexing', () => {
  const test = new Fixture()
  const db = new SuggestionDb()
  const initialAddUpdates = test.addUpdatesForExpected()
  applyUpdates(db, initialAddUpdates, test.suggestionContext)
  // Parent lookup.
  expect(db.childIdToParentId.lookup(1)).toEqual(new Set([]))
  expect(db.childIdToParentId.lookup(2)).toEqual(new Set([1]))
  expect(db.childIdToParentId.lookup(3)).toEqual(new Set([2]))
  expect(db.childIdToParentId.lookup(4)).toEqual(new Set([2]))
  expect(db.childIdToParentId.lookup(5)).toEqual(new Set([2]))
  expect(db.childIdToParentId.lookup(6)).toEqual(new Set([1]))
  expect(db.childIdToParentId.lookup(7)).toEqual(new Set([1]))
  expect(db.childIdToParentId.lookup(8)).toEqual(new Set([]))

  // Children lookup.
  expect(db.childIdToParentId.reverseLookup(1)).toEqual(new Set([2, 6, 7]))
  expect(db.childIdToParentId.reverseLookup(2)).toEqual(new Set([3, 4, 5]))
  expect(db.childIdToParentId.reverseLookup(3)).toEqual(new Set([]))
  expect(db.childIdToParentId.reverseLookup(4)).toEqual(new Set([]))
  expect(db.childIdToParentId.reverseLookup(5)).toEqual(new Set([]))
  expect(db.childIdToParentId.reverseLookup(6)).toEqual(new Set([]))
  expect(db.childIdToParentId.reverseLookup(7)).toEqual(new Set([]))
  expect(db.childIdToParentId.reverseLookup(8)).toEqual(new Set([]))

  // Add new entry.
  const newEntryId = initialAddUpdates[initialAddUpdates.length - 1]!.id + 1
  const modifications: lsTypes.SuggestionsDatabaseUpdate[] = [
    {
      type: 'Add',
      id: newEntryId,
      suggestion: {
        type: 'method',
        module: 'Standard.Base.Main',
        name: 'method2',
        selfType: 'Standard.Base.Main.Type',
        isStatic: false,
        arguments: [],
        returnType: 'Standard.Base.Number',
        documentation: '',
        annotations: [],
      },
    },
  ]
  applyUpdates(db, modifications, test.suggestionContext)
  expect(db.childIdToParentId.lookup(newEntryId)).toEqual(new Set([2]))
  expect(db.childIdToParentId.reverseLookup(newEntryId)).toEqual(new Set([]))
  expect(db.childIdToParentId.reverseLookup(2)).toEqual(new Set([3, 4, 5, newEntryId]))

  // Remove entry.
  const modifications2: lsTypes.SuggestionsDatabaseUpdate[] = [{ type: 'Remove', id: 3 }]
  applyUpdates(db, modifications2, test.suggestionContext)
  expect(db.childIdToParentId.lookup(3)).toEqual(new Set([]))
  expect(db.childIdToParentId.reverseLookup(2)).toEqual(new Set([4, 5, newEntryId]))

  // Modify entry. Moving new method from `Standard.Base.Type` to `Standard.Base`.
  const modifications3: lsTypes.SuggestionsDatabaseUpdate[] = [
    { type: 'Modify', id: newEntryId, selfType: { tag: 'Set', value: 'Standard.Base.Main' } },
  ]
  applyUpdates(db, modifications3, test.suggestionContext)
  expect(db.childIdToParentId.reverseLookup(1)).toEqual(new Set([2, 6, 7, newEntryId]))
  expect(db.childIdToParentId.lookup(newEntryId)).toEqual(new Set([1]))
  expect(db.childIdToParentId.reverseLookup(newEntryId)).toEqual(new Set([]))
  expect(db.childIdToParentId.reverseLookup(2)).toEqual(new Set([4, 5]))
})

test("Modifying suggestion entries' fields", () => {
  const scope2 = {
    start: { line: 1, character: 20 },
    end: { line: 20, character: 1 },
  }
  const typeDocs2 = 'ALIAS Test Type 2\n\nA Test type 2'
  const test = new Fixture()
  const modifications: lsTypes.SuggestionsDatabaseUpdate[] = [
    {
      type: 'Modify',
      id: 1,
      module: { tag: 'Set', value: 'Standard.Base2' },
      reexport: { tag: 'Set', value: 'Standard.Base.Yet.Another.Module' },
    },
    {
      type: 'Modify',
      id: 2,
      module: { tag: 'Set', value: 'Standard.Base2.Main' },
      documentation: { tag: 'Set', value: typeDocs2 },
    },
    { type: 'Modify', id: 3, returnType: { tag: 'Set', value: 'Standard.Base2.Main.Type' } },
    { type: 'Modify', id: 4, selfType: { tag: 'Set', value: 'Standard.Base2.Main.Type' } },
    { type: 'Modify', id: 5, selfType: { tag: 'Set', value: 'Standard.Base2.Main.Type' } },
    { type: 'Modify', id: 6, scope: { tag: 'Set', value: scope2 } },
  ]
  const db = test.createDbWithExpected()
  test.expectedModule.name = unwrap(tryIdentifier('Base2'))
  test.expectedModule.definedIn = stdPath('Standard.Base2')
  test.expectedModule.definitionPath = stdPath('Standard.Base2')
  test.expectedModule.returnType = () => 'Standard.Base2'
  test.expectedModule.reexportedIn = stdPath('Standard.Base.Yet.Another.Module')
  test.expectedType.definedIn = stdPath('Standard.Base2.Main')
  test.expectedType.definitionPath = stdPath('Standard.Base2.Main.Type')
  test.expectedType.returnType = () => 'Standard.Base2.Type'
  test.expectedType.aliases = ['Test Type 2']
  test.expectedType.documentation = parseDocs(typeDocs2)
  test.expectedCon.memberOf = stdPath('Standard.Base2.Main.Type')
  test.expectedCon.definitionPath = stdPath('Standard.Base2.Main.Type.Con')
  test.expectedCon.returnType = () => unwrap(tryQualifiedName('Standard.Base2.Type'))
  test.expectedMethod.memberOf = stdPath('Standard.Base2.Main.Type')
  test.expectedMethod.selfType = stdPath('Standard.Base2.Main.Type')
  test.expectedMethod.definitionPath = stdPath('Standard.Base2.Main.Type.method')
  test.expectedStaticMethod.memberOf = stdPath('Standard.Base2.Main.Type')
  test.expectedStaticMethod.definitionPath = stdPath('Standard.Base2.Main.Type.static_method')
  test.expectedFunction.scope = scope2

  applyUpdates(db, modifications, test.suggestionContext)
  test.check(db)
})

test("Unsetting suggestion entries' fields", () => {
  const test = new Fixture()
  const modifications: lsTypes.SuggestionsDatabaseUpdate[] = [
    {
      type: 'Modify',
      id: 1,
      reexport: { tag: 'Remove' },
    },
    {
      type: 'Modify',
      id: 2,
      documentation: { tag: 'Remove' },
    },
    { type: 'Modify', id: 3, documentation: { tag: 'Remove' } },
    { type: 'Modify', id: 4, documentation: { tag: 'Remove' } },
  ]
  const db = test.createDbWithExpected()
  test.expectedModule.reexportedIn = undefined
  test.expectedType.documentation = []
  test.expectedType.aliases = []
  test.expectedCon.documentation = []
  test.expectedCon.isUnstable = false
  test.expectedMethod.documentation = []
  test.expectedMethod.groupIndex = undefined

  applyUpdates(db, modifications, test.suggestionContext)
  test.check(db)
})

test('Removing entries from database', () => {
  const test = new Fixture()
  const update: lsTypes.SuggestionsDatabaseUpdate[] = [
    { type: 'Remove', id: 2 },
    { type: 'Remove', id: 6 },
  ]
  const db = test.createDbWithExpected()
  applyUpdates(db, update, test.suggestionContext)
  expect(db.get(1)).toBeDefined()
  expect(db.get(2)).toBeUndefined()
  expect(db.get(3)).toBeDefined()
  expect(db.get(4)).toBeDefined()
  expect(db.get(5)).toBeDefined()
  expect(db.get(6)).toBeUndefined()
  expect(db.get(7)).toBeDefined()
  expect(db.get(8)).toBeDefined()
})

test('Adding new argument', () => {
  const test = new Fixture()
  const newArg: lsTypes.SuggestionEntryArgument = {
    name: 'c',
    reprType: 'Any',
    hasDefault: false,
    isSuspended: false,
  }
  const modifications: lsTypes.SuggestionsDatabaseUpdate[] = [
    { type: 'Modify', id: 2, arguments: [{ type: 'Add', index: 0, argument: newArg }] },
    { type: 'Modify', id: 3, arguments: [{ type: 'Add', index: 1, argument: newArg }] },
    { type: 'Modify', id: 5, arguments: [{ type: 'Add', index: 1, argument: newArg }] },
  ]
  const db = test.createDbWithExpected()
  test.expectedType.arguments = [newArg, test.arg1]
  test.expectedCon.arguments = [test.arg1, newArg]
  test.expectedStaticMethod.arguments = [test.arg1, newArg, test.arg2]

  applyUpdates(db, modifications, test.suggestionContext)
  test.check(db)
})

test('Modifying arguments', () => {
  const newArg1 = {
    name: 'c',
    reprType: 'Standard.Base.Number',
    isSuspended: true,
    hasDefault: false,
  }
  const newArg2 = {
    name: 'b',
    reprType: 'Any',
    isSuspended: false,
    hasDefault: true,
    defaultValue: 'Nothing',
  }
  const test = new Fixture()
  const modifications: lsTypes.SuggestionsDatabaseUpdate[] = [
    {
      type: 'Modify',
      id: 5,
      arguments: [
        {
          type: 'Modify',
          index: 0,
          name: { tag: 'Set', value: 'c' },
          reprType: { tag: 'Set', value: 'Standard.Base.Number' },
          isSuspended: { tag: 'Set', value: true },
          hasDefault: { tag: 'Set', value: false },
          defaultValue: { tag: 'Remove' },
        },
        {
          type: 'Modify',
          index: 1,
          hasDefault: { tag: 'Set', value: true },
          defaultValue: { tag: 'Set', value: 'Nothing' },
        },
      ],
    },
  ]
  const db = test.createDbWithExpected()
  test.expectedStaticMethod.arguments = [newArg1, newArg2]

  applyUpdates(db, modifications, test.suggestionContext)
  test.check(db)
})

test('Removing Arguments', () => {
  const test = new Fixture()
  const update: lsTypes.SuggestionsDatabaseUpdate[] = [
    { type: 'Modify', id: 4, arguments: [{ type: 'Remove', index: 0 }] },
    { type: 'Modify', id: 5, arguments: [{ type: 'Remove', index: 1 }] },
  ]
  const db = test.createDbWithExpected()
  test.expectedMethod.arguments = []
  test.expectedStaticMethod.arguments = [test.arg1]

  applyUpdates(db, update, test.suggestionContext)
  test.check(db)
})

function suggestionEntry<T>(data: SuggestionEntry & { kind: T }): SuggestionEntry & { kind: T } {
  return data
}

class Fixture {
  suggestionContext = {
    groups: [
      { name: 'Test1', project: unwrap(tryQualifiedName('Standard.Base')) },
      { name: 'Test2', project: unwrap(tryQualifiedName('Standard.Base')) },
    ],
    currentProject: 'local.Mock_Project' as QualifiedName,
  }
  arg1 = {
    name: 'a',
    reprType: 'Any',
    isSuspended: false,
    hasDefault: true,
    defaultValue: 'Nothing',
  }
  arg2 = {
    name: 'b',
    reprType: 'Any',
    isSuspended: false,
    hasDefault: false,
  }
  scope = {
    start: { line: 1, character: 10 },
    end: { line: 10, character: 1 },
  }
  moduleDocs = 'A base module'
  typeDocs = 'ALIAS Test Type\n\nA Test type'
  conDocs = 'ADVANCED\n\nA Constructor'
  methodDocs = 'GROUP Test1\n\nAn instance method'
  staticMethodDocs = 'GROUP Test2\n\nA static method'
  functionDocs = 'A local function'
  localDocs = 'A local variable'
  expectedModule = suggestionEntry<SuggestionKind.Module>({
    kind: SuggestionKind.Module,
    name: unwrap(tryIdentifier('Base')),
    definedIn: stdPath('Standard.Base.Main'),
    definitionPath: stdPath('Standard.Base.Main'),
    returnType: () => 'Standard.Base',
    documentation: parseDocs(this.moduleDocs),
    reexportedIn: stdPath('Standard.Base.Another.Module'),
    aliases: [],
    isPrivate: false,
    isUnstable: false,
    iconName: undefined,
    groupIndex: undefined,
  })
  expectedType = suggestionEntry<SuggestionKind.Type>({
    kind: SuggestionKind.Type,
    name: unwrap(tryIdentifier('Type')),
    definedIn: stdPath('Standard.Base.Main'),
    definitionPath: stdPath('Standard.Base.Main.Type'),
    arguments: [this.arg1],
    returnType: () => 'Standard.Base.Type',
    documentation: parseDocs(this.typeDocs),
    aliases: ['Test Type'],
    isPrivate: false,
    isUnstable: false,
    parentType: undefined,
    reexportedIn: stdPath('Standard.Base.Another.Module'),
    iconName: undefined,
    groupIndex: undefined,
  })
  expectedCon = suggestionEntry<SuggestionKind.Constructor>({
    kind: SuggestionKind.Constructor,
    name: unwrap(tryIdentifier('Con')),
    definedIn: stdPath('Standard.Base.Main'),
    memberOf: stdPath('Standard.Base.Main.Type'),
    definitionPath: stdPath('Standard.Base.Main.Type.Con'),
    arguments: [this.arg1],
    returnType: () => 'Standard.Base.Type',
    documentation: parseDocs(this.conDocs),
    aliases: [],
    isPrivate: false,
    isUnstable: true,
    reexportedIn: stdPath('Standard.Base.Another.Module'),
    annotations: ['Annotation 1'],
    iconName: undefined,
    groupIndex: undefined,
  })
  expectedMethod = suggestionEntry<SuggestionKind.Method>({
    kind: SuggestionKind.Method,
    name: unwrap(tryIdentifier('method')),
    definedIn: stdPath('Standard.Base.Main'),
    memberOf: stdPath('Standard.Base.Main.Type'),
    definitionPath: stdPath('Standard.Base.Main.Type.method'),
    selfType: stdPath('Standard.Base.Main.Type'),
    arguments: [this.arg1],
    returnType: () => 'Standard.Base.Number',
    documentation: parseDocs(this.methodDocs),
    groupIndex: 0,
    aliases: [],
    isPrivate: false,
    isUnstable: false,
    annotations: ['Annotation 2', 'Annotation 3'],
    iconName: undefined,
    reexportedIn: undefined,
  })
  expectedStaticMethod = suggestionEntry<SuggestionKind.Method>({
    kind: SuggestionKind.Method,
    name: unwrap(tryIdentifier('static_method')),
    definedIn: stdPath('Standard.Base.Main'),
    memberOf: stdPath('Standard.Base.Main.Type'),
    definitionPath: stdPath('Standard.Base.Main.Type.static_method'),
    arguments: [this.arg1, this.arg2],
    returnType: () => 'Standard.Base.Number',
    documentation: parseDocs(this.staticMethodDocs),
    groupIndex: 1,
    aliases: [],
    isPrivate: false,
    isUnstable: false,
    reexportedIn: stdPath('Standard.Base.Another.Module'),
    annotations: [],
    iconName: undefined,
    selfType: undefined,
  })
  expectedFunction = suggestionEntry<SuggestionKind.Function>({
    kind: SuggestionKind.Function,
    name: unwrap(tryIdentifier('function')),
    definedIn: stdPath('Standard.Base.Main'),
    definitionPath: stdPath('Standard.Base.Main.function'),
    arguments: [this.arg1],
    returnType: () => 'Standard.Base.Number',
    documentation: parseDocs(this.functionDocs),
    aliases: [],
    isPrivate: false,
    isUnstable: false,
    scope: this.scope,
    iconName: undefined,
    groupIndex: undefined,
  })
  expectedLocal = suggestionEntry<SuggestionKind.Local>({
    kind: SuggestionKind.Local,
    name: unwrap(tryIdentifier('local')),
    definedIn: stdPath('Standard.Base.Main'),
    definitionPath: stdPath('Standard.Base.Main.local'),
    returnType: () => 'Standard.Base.Number',
    documentation: parseDocs(this.localDocs),
    aliases: [],
    isPrivate: false,
    isUnstable: false,
    scope: this.scope,
    iconName: undefined,
    groupIndex: undefined,
  })
  expectedLocalStaticMethod = suggestionEntry<SuggestionKind.Method>({
    kind: SuggestionKind.Method,
    arguments: [
      {
        name: 'a',
        reprType: 'Standard.Base.Any.Any',
        isSuspended: false,
        hasDefault: false,
        defaultValue: null,
        tagValues: null,
      },
    ],
    annotations: [],
    name: unwrap(tryIdentifier('collapsed')),
    definedIn: ProjectPath.create(undefined, 'Main' as Identifier),
    definitionPath: ProjectPath.create(undefined, 'Main.collapsed' as QualifiedName),
    documentation: [{ Tag: { tag: 'Icon', body: 'group' } }, { Paragraph: { body: '' } }],
    iconName: 'group',
    aliases: [],
    isPrivate: false,
    isUnstable: false,
    memberOf: ProjectPath.create(undefined, 'Main' as Identifier),
    returnType: () => 'Standard.Base.Any.Any',
    groupIndex: undefined,
    selfType: undefined,
    reexportedIn: undefined,
  })

  addUpdatesForExpected(): lsTypes.SuggestionsDatabaseUpdate[] {
    return [
      {
        type: 'Add',
        id: 1,
        suggestion: {
          type: 'module',
          module: 'Standard.Base.Main',
          documentation: this.moduleDocs,
          reexport: 'Standard.Base.Another.Module',
        },
      },
      {
        type: 'Add',
        id: 2,
        suggestion: {
          type: 'type',
          module: 'Standard.Base.Main',
          name: 'Type',
          params: [this.arg1],
          documentation: this.typeDocs,
          reexport: 'Standard.Base.Another.Module',
        },
      },
      {
        type: 'Add',
        id: 3,
        suggestion: {
          type: 'constructor',
          module: 'Standard.Base.Main',
          name: 'Con',
          arguments: [this.arg1],
          returnType: 'Standard.Base.Main.Type',
          documentation: this.conDocs,
          reexport: 'Standard.Base.Another.Module',
          annotations: ['Annotation 1'],
        },
      },
      {
        type: 'Add',
        id: 4,
        suggestion: {
          type: 'method',
          module: 'Standard.Base.Main',
          name: 'method',
          selfType: 'Standard.Base.Main.Type',
          isStatic: false,
          arguments: [this.arg1],
          returnType: 'Standard.Base.Number',
          documentation: this.methodDocs,
          annotations: ['Annotation 2', 'Annotation 3'],
        },
      },
      {
        type: 'Add',
        id: 5,
        suggestion: {
          type: 'method',
          module: 'Standard.Base.Main',
          name: 'static_method',
          selfType: 'Standard.Base.Main.Type',
          isStatic: true,
          arguments: [this.arg1, this.arg2],
          returnType: 'Standard.Base.Number',
          documentation: this.staticMethodDocs,
          reexport: 'Standard.Base.Another.Module',
          annotations: [],
        },
      },
      {
        type: 'Add',
        id: 6,
        suggestion: {
          type: 'function',
          module: 'Standard.Base.Main',
          name: 'function',
          arguments: [this.arg1],
          returnType: 'Standard.Base.Number',
          scope: this.scope,
          documentation: this.functionDocs,
        },
      },
      {
        type: 'Add',
        id: 7,
        suggestion: {
          type: 'local',
          module: 'Standard.Base.Main',
          name: 'local',
          returnType: 'Standard.Base.Number',
          scope: this.scope,
          documentation: this.localDocs,
        },
      },
      {
        type: 'Add',
        id: 8,
        suggestion: {
          type: 'method',
          module: 'local.Mock_Project.Main',
          name: 'collapsed',
          arguments: [
            {
              name: 'a',
              reprType: 'Standard.Base.Any.Any',
              isSuspended: false,
              hasDefault: false,
              defaultValue: null,
              tagValues: null,
            },
          ],
          selfType: 'local.Mock_Project.Main',
          returnType: 'Standard.Base.Any.Any',
          isStatic: true,
          documentation: ' ICON group',
          annotations: [],
        },
      },
    ]
  }

  createDbWithExpected(): SuggestionDb {
    const db = new SuggestionDb()
    applyUpdates(db, this.addUpdatesForExpected(), this.suggestionContext)
    return db
  }

  check(db: SuggestionDb): void {
    expectPropertiesToStrictEqual(db.get(1), this.expectedModule)
    expectPropertiesToStrictEqual(db.get(2), this.expectedType)
    expectPropertiesToStrictEqual(db.get(3), this.expectedCon)
    expectPropertiesToStrictEqual(db.get(4), this.expectedMethod)
    expectPropertiesToStrictEqual(db.get(5), this.expectedStaticMethod)
    expectPropertiesToStrictEqual(db.get(6), this.expectedFunction)
    expectPropertiesToStrictEqual(db.get(7), this.expectedLocal)
    expectPropertiesToStrictEqual(db.get(8), this.expectedLocalStaticMethod)
  }
}

function expectPropertiesToStrictEqual(actual: unknown, expected: object): void {
  expect(extractProperties(expected, actual)).toStrictEqual(callFunctions(expected))
}

function extractProperties(reference: object, value: unknown): object {
  expect(typeof value).toBe('object')
  expect(value).not.toBeNull()
  assert(typeof value === 'object' && value !== null)
  const result = {}
  for (const key in reference) {
    if (key in value) {
      const fieldValue = (value as any)[key]
      Object.assign(result, {
        [key]: typeof fieldValue === 'function' ? fieldValue.call(value, projectNames) : fieldValue,
      })
    }
  }
  return result
}

function callFunctions(value: object): object {
  const result = {}
  for (const key in value) {
    const fieldValue = (value as any)[key]
    Object.assign(result, {
      [key]: typeof fieldValue === 'function' ? fieldValue.call(value, projectNames) : fieldValue,
    })
  }
  return result
}
