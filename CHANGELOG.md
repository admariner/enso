# Next Release

#### Enso IDE

- [Rows and Columns may be now removed in Table Input Widget][11151]. The option
  is available in right-click context menu.
- [Rows and Columns may be now reordered by dragging in Table Input
  Widget][11271]
- [Copying and pasting in Table Editor Widget now works properly][11332]
- [Fix invisible selection in Table Input Widget][11358]
- [Enable cloud file browser in local projects][11383]
- [Changed the way of adding new column in Table Input Widget][11388]. The
  "virtual column" is replaced with an explicit (+) button.
- [New dropdown-based component menu][11398].
- [Methods defined on Standard.Base.Any type are now visible on all
  components][11451].
- [Undo/redo buttons in the top bar][11433].
- [Size of Table Input Widget is preserved and restored after project
  re-opening][11435]
- [Added application version to the title bar.][11446]
- [Added "open grouped components" action to the context menu.][11447]
- [Table Input Widget has now a limit of 256 cells.][11448]
- [Added an error message screen displayed when viewing a deleted
  component.][11452]
- [New documentation editor provides improved Markdown editing experience, and
  paves the way for new documentation features.][11469]
- [You can now add images to documentation panel][11547] by pasting them from
  clipboard or by drag'n'dropping image files.
- ["Write" button in component menu allows to evaluate it separately from the
  rest of the workflow][11523].
- [The documentation editor can now display tables][11564]
- [The documentation editor supports the Markdown URL syntax, and uses it to
  render pasted URLs as links][11597]
- [Table Input Widget is now matched for Table.input method instead of
  Table.new. Values must be string literals, and their content is parsed to the
  suitable type][11612].
- [Added dedicated function signature viewer and editor in the right-side
  panel][11655].
- [Visualizations on components are slightly transparent when not
  focused][11582].
- [New design for vector-editing widget][11620]
- [The component menu can be opened by right-click; supports operations on
  multiple components; has a 'Copy Component' button][11690]
- [New design for vector-editing widget][11620].
- [Default values on widgets are displayed in italic][11666].
- [Fixed bug causing Table Visualization to show wrong data][11684].
- [Pasting tabular data now creates Table.input expressions][11695].
- [No halo is displayed around components when hovering][11715].
- [The hover area of the component output port extended twice its size][11715].
- [The documentation editor and comment documentation support opening links with
  a key pressed, or via a popup when editing][11753].
- [Fixed a rare bug where the component position wasn't persisted after closing
  project][11761]
- [In the table visualization and table widget, the table context menu can now
  be opened on OS X][11755].
- [Fix some UI elements drawing on top of visualization toolbar dropdown
  menus][11768].
- [Edges are now colored based on their source component.][11810]
- [Highlight missing required arguments][11803].
- [Arrows in some drop-down buttons are now clearly visible][11800]

[11151]: https://github.com/enso-org/enso/pull/11151
[11271]: https://github.com/enso-org/enso/pull/11271
[11332]: https://github.com/enso-org/enso/pull/11332
[11358]: https://github.com/enso-org/enso/pull/11358
[11383]: https://github.com/enso-org/enso/pull/11383
[11388]: https://github.com/enso-org/enso/pull/11388
[11398]: https://github.com/enso-org/enso/pull/11398
[11451]: https://github.com/enso-org/enso/pull/11451
[11433]: https://github.com/enso-org/enso/pull/11433
[11435]: https://github.com/enso-org/enso/pull/11435
[11446]: https://github.com/enso-org/enso/pull/11446
[11447]: https://github.com/enso-org/enso/pull/11447
[11448]: https://github.com/enso-org/enso/pull/11448
[11452]: https://github.com/enso-org/enso/pull/11452
[11469]: https://github.com/enso-org/enso/pull/11469
[11547]: https://github.com/enso-org/enso/pull/11547
[11523]: https://github.com/enso-org/enso/pull/11523
[11564]: https://github.com/enso-org/enso/pull/11564
[11582]: https://github.com/enso-org/enso/pull/11582
[11597]: https://github.com/enso-org/enso/pull/11597
[11612]: https://github.com/enso-org/enso/pull/11612
[11655]: https://github.com/enso-org/enso/pull/11655
[11582]: https://github.com/enso-org/enso/pull/11582
[11620]: https://github.com/enso-org/enso/pull/11620
[11666]: https://github.com/enso-org/enso/pull/11666
[11690]: https://github.com/enso-org/enso/pull/11690
[11684]: https://github.com/enso-org/enso/pull/11684
[11695]: https://github.com/enso-org/enso/pull/11695
[11715]: https://github.com/enso-org/enso/pull/11715
[11753]: https://github.com/enso-org/enso/pull/11753
[11761]: https://github.com/enso-org/enso/pull/11761
[11768]: https://github.com/enso-org/enso/pull/11768
[11810]: https://github.com/enso-org/enso/pull/11810
[11803]: https://github.com/enso-org/enso/pull/11803
[11800]: https://github.com/enso-org/enso/pull/11800

#### Enso Standard Library

- [The `enso://~` path now resolves to user's home directory in the
  cloud.][11235]
- [The user may set description and labels of an Enso Cloud asset
  programmatically.][11255]
- [DB_Table may be saved as a Data Link.][11371]
- [Support for dates before 1900 in Excel and signed AWS requests.][11373]
- [Added `Data.read_many` that allows to read a list of files in a single
  operation.][11490]
- [Added `Table.input` allowing creation of typed tables from vectors of data,
  including auto parsing text columns.][11562]
- [Enhance Managed_Resource to allow implementation of in-memory caches][11577]
- [The reload button clears the HTTP cache.][11673]

[11235]: https://github.com/enso-org/enso/pull/11235
[11255]: https://github.com/enso-org/enso/pull/11255
[11371]: https://github.com/enso-org/enso/pull/11371
[11373]: https://github.com/enso-org/enso/pull/11373
[11490]: https://github.com/enso-org/enso/pull/11490
[11562]: https://github.com/enso-org/enso/pull/11562
[11577]: https://github.com/enso-org/enso/pull/11577
[11673]: https://github.com/enso-org/enso/pull/11673

#### Enso Language & Runtime

- [Arguments in constructor definitions may now be on their own lines][11374]
- [The `:` type operator can now be chained][11671].

[11374]: https://github.com/enso-org/enso/pull/11374
[11671]: https://github.com/enso-org/enso/pull/11671

# Enso 2024.4

#### Enso IDE

- [Table Editor Widget][10774] displayed in `Table.new` component.
- [New design of Component Browser][10814] - the component list is under the
  input and shown only in the initial "component browsing" mode - in this mode
  the entire input is a filtering pattern (it is not interpreted as parts of
  code). After picking any suggestion with Tab or new button the mode is
  switched to "code editing", where visualization preview is displayed instead.
  Also the component browser help is now displayed in the right-side dock panel.
- [Drilldown for XML][10824]
- [Fixed issue where switching edited widget with <kbd>tab</kbd> key did not
  updated actual code][10857]
- [Added fullscreen modes to documentation editor and code editor][10876]
- [Fixed issue with node name assignment when uploading multiple files.][10979]
- [Cloud file browser inserts `enso:` paths][11001]
- [Fixed issue where drag'n'dropped files were not uploaded in cloud
  projects.][11014]
- [Fixed files associations not properly registered on Windows][11030]
- [Input components corresponding to function arguments are now
  displayed.][11165]
- [Fixed "rename project" button being broken after not changing project
  name][11103]
- [Numbers starting with dot (`.5`) are accepted in Numeric Widget][11108]
- [Add support for interacting with graph editor using touch devices.][11056]

[10774]: https://github.com/enso-org/enso/pull/10774
[10814]: https://github.com/enso-org/enso/pull/10814
[10824]: https://github.com/enso-org/enso/pull/10824
[10857]: https://github.com/enso-org/enso/pull/10857
[10876]: https://github.com/enso-org/enso/pull/10876
[10979]: https://github.com/enso-org/enso/pull/10979
[11001]: https://github.com/enso-org/enso/pull/11001
[11014]: https://github.com/enso-org/enso/pull/11014
[11030]: https://github.com/enso-org/enso/pull/11030
[11165]: https://github.com/enso-org/enso/pull/11165
[11103]: https://github.com/enso-org/enso/pull/11103
[11108]: https://github.com/enso-org/enso/pull/11108
[11056]: https://github.com/enso-org/enso/pull/11056

#### Enso Standard Library

- [Implemented in-memory and database mixed `Decimal` column
  comparisons.][10614]
- [Relative paths are now resolved relative to the project location, also in the
  Cloud.][10660]
- [Added Newline option to Text_Cleanse/Text_Replace.][10761]
- [Support for reading from Tableau Hyper files.][10733]
- [Mixed Decimal/Float arithmetic now throws an error; mixed comparisons now
  attach warnings.][10725]
- [Support for creating Atoms in expressions.][10820]
- [IO.print without new line][10858]
- [Add `Text.to_decimal`.][10874]
- [Added `floor`, `ceil`, `trunc` to the in-memory `Decimal` column.][10887]
- [Added vectorized .round to the in-memory `Decimal` column.][10912]
- [`select_into_database_table` no longer defaults the primary key to the first
  column.][11120]
- [Extend the range of `floor`, `ceil`, `trunc` to values outside the `Long`
  range.][11135]
- [Added `format` parameter to `Decimal.parse`.][11205]
- [Added `format` parameter to `Float.parse`.][11229]
- [Implemented a cache for HTTP data requests, as well as a per-file response
  size limit.][11342]
- [Overhauled Google Analytics APIs.][11484]

[10614]: https://github.com/enso-org/enso/pull/10614
[10660]: https://github.com/enso-org/enso/pull/10660
[10761]: https://github.com/enso-org/enso/pull/10761
[10733]: https://github.com/enso-org/enso/pull/10733
[10725]: https://github.com/enso-org/enso/pull/10725
[10820]: https://github.com/enso-org/enso/pull/10820
[10858]: https://github.com/enso-org/enso/pull/10858
[10874]: https://github.com/enso-org/enso/pull/10874
[10887]: https://github.com/enso-org/enso/pull/10887
[10912]: https://github.com/enso-org/enso/pull/10912
[11120]: https://github.com/enso-org/enso/pull/11120
[11135]: https://github.com/enso-org/enso/pull/11135
[11205]: https://github.com/enso-org/enso/pull/11205
[11229]: https://github.com/enso-org/enso/pull/11229
[11342]: https://github.com/enso-org/enso/pull/11342
[11484]: https://github.com/enso-org/enso/pull/11484

#### Enso Language & Runtime

- [Print out warnings associated with local variables][10842]

[10842]: https://github.com/enso-org/enso/pull/10842

# Enso 2024.3

#### Enso Language & Runtime

- [Enforce conversion method return type][10468]
- [Renaming launcher executable to ensoup][10535]
- [Space-precedence does not apply to value-level operators][10597]
- [Must specify `--repl` to enable debug server][10709]
- [Improved parser error reporting and performance][10734]

[10468]: https://github.com/enso-org/enso/pull/10468
[10535]: https://github.com/enso-org/enso/pull/10535
[10597]: https://github.com/enso-org/enso/pull/10597
[10709]: https://github.com/enso-org/enso/pull/10709
[10734]: https://github.com/enso-org/enso/pull/10734

#### Enso IDE

- ["Add node" button is not obscured by output port][10433]
- [Numeric Widget does not accept non-numeric input][10457]. This is to prevent
  node being completely altered by accidental code put to the widget.
- [Redesigned "record control" panel][10509]. Now it contains more intuitive
  "refresh" and "write all" buttons.
- [Warning messages do not obscure visualization buttons][10546].
- [Output component in collapsed function changed][10577]. It cannot be deleted
  anymore, except by directily editing the code.
- [Improved handling of spacing around rounded node widgets][10599], added
  support for widgets of arbitrary sizes.
- [Multiselect drop-down widget visuals are improved][10607].
- [Text displayed in monospace and whitespace rendered as symbols][10563].

[10433]: https://github.com/enso-org/enso/pull/10443
[10457]: https://github.com/enso-org/enso/pull/10457
[10509]: https://github.com/enso-org/enso/pull/10509
[10546]: https://github.com/enso-org/enso/pull/10546
[10577]: https://github.com/enso-org/enso/pull/10577
[10599]: https://github.com/enso-org/enso/pull/10599
[10607]: https://github.com/enso-org/enso/pull/10607
[10563]: https://github.com/enso-org/enso/pull/10563

#### Enso Standard Library

- [Renamed `Data.list_directory` to `Data.list`. Removed list support from read
  methods.][10434]
- [Renamed `Location.Start` to `Location.Left` and `Location.End` to
  `Location.Right`.][10445]
- [Renamed `Postgres_Details.Postgres` to `Postgres.Server`.][10466]
- [Remove `First` and `Last` from namespace, use auto-scoped.][10467]
- [Rename `Map` to `Dictionary` and `Set` to `Hashset`.][10474]
- [Compare two objects with `Ordering.compare` and define comparator with
  `Comparable.new`][10468]
- [Added `dec` construction function for creating `Decimal`s.][10517]
- [Added initial read support for SQLServer][10324]
- [Upgraded SQLite to version 3.46.1.][10911]

[10434]: https://github.com/enso-org/enso/pull/10434
[10445]: https://github.com/enso-org/enso/pull/10445
[10466]: https://github.com/enso-org/enso/pull/10466
[10467]: https://github.com/enso-org/enso/pull/10467
[10474]: https://github.com/enso-org/enso/pull/10474
[10517]: https://github.com/enso-org/enso/pull/10517
[10324]: https://github.com/enso-org/enso/pull/10324
[10911]: https://github.com/enso-org/enso/pull/10911

# Enso 2024.2

#### Enso IDE

- [Arrows navigation][10179] selected nodes may be moved around, or entire scene
  if no node is selected.
- [Added a limit for dropdown width][10198], implemented ellipsis and scrolling
  for long labels when hovered.
- [Copy-pasting multiple nodes][10194].
- The documentation editor has [formatting toolbars][10064].
- The documentation editor supports [rendering images][10205].
- [Project may be renamed in Project View][10243]
- [Fixed a bug where drop-down were not displayed for some arguments][10297].
  For example, `locale` parameter of `Equal_Ignore_Case` kind in join component.
- [Node previews][10310]: Node may be previewed by hovering output port while
  pressing <kbd>Ctrl</kbd> key (<kbd>Cmd</kbd> on macOS).
- [Google Sheets clipboard support][10327]: Create a Table component when cells
  are pasted from Google Sheets.
- [Fixed issue with two arrows being visible at once in drop-down
  widget.][10337]
- [Fixed issue where picking "<Numeric literal>" variant in some ports
  disallowed changing it again.][10337]
- [Added click through on table and vector visualisation][10340] clicking on
  index column will select row or value in seperate node
- [Copied table-viz range pastes as Table component][10352]
- [Added support for links in documentation panels][10353].
- [Added support for opening documentation in an external browser][10396].
- Added a [cloud file browser][10513].

[10064]: https://github.com/enso-org/enso/pull/10064
[10179]: https://github.com/enso-org/enso/pull/10179
[10194]: https://github.com/enso-org/enso/pull/10194
[10198]: https://github.com/enso-org/enso/pull/10198
[10205]: https://github.com/enso-org/enso/pull/10205
[10243]: https://github.com/enso-org/enso/pull/10243
[10297]: https://github.com/enso-org/enso/pull/10297
[10310]: https://github.com/enso-org/enso/pull/10310
[10327]: https://github.com/enso-org/enso/pull/10327
[10337]: https://github.com/enso-org/enso/pull/10337
[10340]: https://github.com/enso-org/enso/pull/10340
[10352]: https://github.com/enso-org/enso/pull/10352
[10353]: https://github.com/enso-org/enso/pull/10353
[10396]: https://github.com/enso-org/enso/pull/10396
[10513]: https://github.com/enso-org/enso/pull/10513

#### Enso Language & Runtime

- Support for [explicit --jvm option][10374] when launching `enso` CLI

[10374]: https://github.com/enso-org/enso/pull/10374

#### Enso Standard Library

- [Added Statistic.Product][10122]
- [Added Encoding.Default that tries to detect UTF-8 or UTF-16 encoding based on
  BOM][10130]
- [Added `Decimal` column to the in-memory database, with some arithmetic
  operations.][9950]
- [Implemented `.cast` to and from `Decimal` columns for the in-memory
  database.][10206]
- [Implemented fallback to Windows-1252 encoding for `Encoding.Default`.][10190]
- [Added Table.duplicates component][10323]
- [Renamed `Table.order_by` to `Table.sort`][10372]
- [Implemented `Decimal` support for Postgres backend.][10216]

[debug-shortcuts]:

[9950]: https://github.com/enso-org/enso/pull/9950
[10122]: https://github.com/enso-org/enso/pull/10122
[10130]: https://github.com/enso-org/enso/pull/10130
[10206]: https://github.com/enso-org/enso/pull/10206
[10190]: https://github.com/enso-org/enso/pull/10190
[10323]: https://github.com/enso-org/enso/pull/10323
[10372]: https://github.com/enso-org/enso/pull/10372
[10216]: https://github.com/enso-org/enso/pull/10216

<br/>![Release Notes](/docs/assets/tags/release_notes.svg)

#### Anonymous Data Collection

Please note that this release collects anonymous usage data which will be used
to improve Enso and prepare it for a stable release. We will switch to opt-in
data collection in stable version releases. The usage data will not contain your
code (expressions above nodes), however, reported errors may contain brief
snippets of out of context code that specifically leads to the error, like "the
method 'foo' does not exist on Number". The following data will be collected:

- Session length.
- Project management events (project open, close, rename).
- Errors (IDE crashes, Project Manager errors, Language Server errors, Compiler
  errors).
