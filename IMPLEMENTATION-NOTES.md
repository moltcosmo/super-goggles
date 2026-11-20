# Print Job Manager 1.0 — UI foundation

## Implemented in this checkpoint

- Local customer creation and customer list.
- Sales Order creation and order detail.
- Project creation under a Sales Order, with its own quantity, unit, selling price, and deadline.
- Sales Order summary derived from its Projects and valid payment transactions.
- Multiple payments per Sales Order and payment voiding (no physical deletion).
- Workflow templates and template stages.
- Copy template stages into a new Project.
- Add Project-specific stages.
- Sequential stage start/completion enforcement in SQLite.
- Project status changes to active when production starts and completed when all stages are complete.
- Stage estimated completion time and PIC fields can be recorded.
- Project and Sales Order list search with 20-record batches and Load More.

## Added in HPP checkpoint 1.1

- HPP component settings UI for Media, Print Process, Finishing, and Other Costs.
- Create and edit reusable cost components.
- Add selected components to an individual Project's HPP.
- HPP breakdown by category, total HPP, and HPP per Project unit.
- Cost formula: `max(quantity * rate, minimum_charge) + setup_charge`.
- Rate and specification snapshots are stored on each Project cost line; editing a master component does not change existing Project calculations.
- Remove a cost line from a Project calculation.

## Not implemented yet

- Paper layout optimizer calculation engine and diagrams.
- Native Android scheduled notifications and notification permission handling.
- Purchasing UI.
- Export, backup, and restore.
- Attachments/files UI.
- Full editing/reordering/archive management for master records and workflow stages.

## HPP calculation behavior

- `rate_basis` is a descriptive unit for the quantity entered on a Project cost line (for example, lembar, rim, kg, or jam).
- Variable cost is rounded to the nearest Rupiah after multiplying quantity by rate.
- Minimum charge is applied to the variable cost; setup charge is added afterward.
- HPP is a cost estimate. Selling price remains separate on the Project. The UI displays the difference between selling price and HPP as a simple reference, not a full margin/tax analysis.

## Local data

The app uses a SQLite database named `print_job_manager.db` in private app storage. This prototype has no production data and no migration path from earlier prototypes. Uninstalling the app removes its local database.

## Validation performed

- Executed all 54 SQL statements extracted from `MainActivity.java` against SQLite in-memory.
- Confirmed 16 tables, 31 indexes, 5 triggers, and 2 views are created.
- Smoke-tested duplicate Project selling prices, payment balance, and sequential workflow blocking/advance.
- Android APK compilation and on-device behavior still require GitHub Actions build and phone testing; this environment did not have Gradle/Android SDK installed.

## Added in Paper Layout Optimizer checkpoint 1.2

- Standalone calculations and calculations linked to an individual Project.
- Inputs: source sheet dimensions, cut dimensions, required quantity, edge margin, knife kerf, and a rotation/grain constraint.
- Generates up to three guillotine strip patterns: all normal, all rotated, and mixed row orientations when a genuine mixed solution is found.
- Uses a row-based guillotine model: first divide the plano into full-width strips, then divide each strip into pieces. Kerf is included between adjacent pieces and rows; outer margin is applied on all four edges.
- Pattern result includes yield per sheet, number of sheets required, waste area, waste percentage, efficiency, row sequence, and a schematic row diagram.
- User can select one pattern per calculation.
- For a Project-linked calculation, the required plano sheets can be added to HPP using an existing Media cost component. The HPP line stores the selected layout pattern reference and snapshots the media rate/specification.
- No additional database tables or libraries were added; the existing `layout_calculations`, `layout_patterns`, and `project_cost_lines` tables are used.

## Paper Layout Optimizer limitations

- This is a guillotine strip-layout estimator, not a general 2D nesting engine.
- It considers rows spanning the usable sheet width and allows normal/rotated orientation by row. It does not yet model arbitrary multi-stage cut trees, clamps/gripper zones, bleed, registration marks, machine-specific constraints, or irregular shapes.
- Grain selection currently acts as an orientation restriction: free, normal-only, or rotated-only. The operator must confirm actual grain direction and machine constraints before production.
- The diagram is schematic and is not a dimensioned cutting drawing.
- APK build and physical on-device testing remain pending GitHub Actions and phone validation.
