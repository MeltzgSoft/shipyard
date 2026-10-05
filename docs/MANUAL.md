# Shipyard User Manual

Shipyard lets you browse and preview Battlefleet Gothic STL parts you already own and
define the mount frames that describe how those parts connect.

This manual covers supported behavior. Planned work lives in the
[Forgejo milestones](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/milestones).

## 1. Installing

Download the desktop package for your platform from
[Releases](https://github.com/MeltzgSoft/shipyard/releases):

- Linux x64: make the AppImage executable and open it, or install the `.deb`.
- Windows x64: run the `.exe` installer. Install the
  [x64 Visual C++ Redistributable](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)
  if it is not already installed.
- Apple Silicon macOS: open the `.dmg` and drag Shipyard to Applications.

The desktop app includes Java 25. Launch Shipyard to open its window. It starts
its own local backend on the first free port starting at 8080, so another app
using 8080 does not prevent startup. Closing the window stops that backend.
Launching Shipyard a second time focuses the existing window.

Shipyard runs entirely on your own machine: your models stay on your disk.
Run only one Shipyard backend per database. Stop a separately started browser
server before opening the desktop app with the same data directory.

For the browser-based developer workflow, install **Java 25** and check it with
`java -version`. Earlier releases do not support the required JVM options.

Download the jar, or build it (see the README), then:

```bash
java -jar shipyard-0.1.0-SNAPSHOT.jar
```

Open <http://127.0.0.1:8080>. Shipyard runs entirely on your own machine - nothing is
uploaded anywhere, and your models never leave your disk.

To use a different port: `PORT=9000 java -jar shipyard-…jar`

## 2. Pointing Shipyard at your models

Shipyard reads an existing STL library. It never modifies, moves, or copies your files.

Shipyard has no default location, because there is no location it could guess that
would be right. The first time you open it, the library panel asks where your models
are:

1. Open **Settings** in the masthead, then click **Browse…** beside **Library folder**. In the desktop folder
   selector, choose the folder that holds your bundles — the one whose subfolders are
   `Human Navy Fleet Bundle` and the like.
2. Press **Use this folder**.

Shipyard scans it immediately; return to **Part Browser** to see the parts; there is nothing to restart. The
folder is remembered, so every run after this one starts with your library already
loaded.

To change it later, open **Settings → Library folder**. If the
path is wrong - a typo, or a drive that is not mounted - Shipyard says so and keeps
using the folder it already had.

The Swing selectors open on the desktop of the computer running Shipyard. Use the
browser on that same computer. **Cancel** leaves the previous selection unchanged;
choosing a folder does not apply it until you press **Use this folder**. If the folder selector
is unavailable, you can type or paste the library path directly (`~` works). Selectors need
a Java runtime with desktop support and access to the graphical session; no separate
file-dialog packages are required. A headless server still accepts library folder paths.

Your choice is stored in Shipyard's database, alongside your authored metadata.
When upgrading, Shipyard preserves a selection from the old
`$XDG_CONFIG_HOME/shipyard/library.edn` file if the database has no saved selection.
After that, use **Settings → Library folder** to change it; editing the old file does not change
the database selection. Shipyard leaves your system `config.edn` untouched.

### Classification values and mount-cut defaults

In **Settings**, add, rename or delete **Faction**, **Class**, and **Role** values.
Each row shows how many parts and socket acceptance lists use it, including missing
parts and parts in other libraries. **Save** renames the value everywhere without
changing source paths, mount geometry or paint. A name already in use cannot be the
rename destination. **Delete** is available only for unused values. Built-in roles
and the Universal class cannot be renamed or deleted. During import review, library and classification
changes are disabled; finish or cancel the import first.

**Pit and recess defaults** sets pit depth and diameter, and recess depth and border,
in millimeters. Depth and diameter must be positive; border may be zero. Press
**Save defaults** to persist them in the database. New mount cuts use these values;
saved cuts and manually entered dimensions retain their values. Creating a pitted
version still requires checking **Create pitted version** in Mounts. Changing defaults
does not regenerate any STL. Unsaved folder and default fields survive switching
workspaces while the server is running.

### How your library should be organised

Shipyard expects **one folder per part**, holding that part's variants:

```
<Bundle>/[<Class>/][weapons/]<Part Name>/
    unsupported.stl              <- what Shipyard displays
    unsupported-pitted.stl       <- magnet pits; file thumbnail during import only
    supported.stl                <- print scaffolding; thumbnail during import only
```

A folder counts as a part if it holds at least one of those three files. Folders named
`other` are skipped, so slicer projects and README files can live alongside your models
without confusing anything.

Supported-only parts remain catalogued but do not appear in the regular Part Browser.
Parts with both supported and unsupported files appear once, using the unsupported
geometry. Pitted-only parts remain listed with no preview.

Physical cut geometry accepts closed surfaces whose shared edges have balanced
opposing faces, including edge-touching shells. Open boundaries, inconsistent
winding, duplicate or degenerate faces still require repair in a mesh tool.
Source coordinates are preserved; cut generation does not fill holes or resolve
self-intersections.

### Importing a ZIP archive

Choose your library folder first. In **Part Browser**, click **Import ZIP…**.
Browse for the archive or type/paste its full path in the desktop selector's
**File name** field, then choose **Open**. Approval immediately starts import review;
canceling leaves the table unchanged. ZIP import requires a graphical desktop.
Shipyard unpacks
nested ZIPs and switches the table to **Import mode**, listing every STL in that
archive. Non-STL extras, including Lychee projects and incomplete `.part` downloads,
are ignored. Zero-byte nested ZIP files are skipped and listed in the review;
invalid nonempty ZIPs stop extraction with their archive path in the error.
The original archives are always kept.

Bundle/faction, class, role and supported status are inferred from archive, folder and
file names. These are hints: review unknown or ambiguous values. An inner **Original
Files** folder takes precedence over an outer archive labelled **Supported**. Files
without a support marker default to unsupported. The table shows the variant and
original archive paths. Matching names, bundle, class and role are grouped into one
row when each variant has unambiguous content. Identical repeated files can share a
row; competing files with the same variant remain separate for review.

Expand **Files / variants** in a row to see a thumbnail of each original source file
and choose **Supported**,
**Unsupported**, or **Unsupported (pitted)** for each. Changing one side of a clear
pair swaps the other side automatically. Changing the unsupported source clears its
saved orientation so the new geometry can be reviewed.
File thumbnails load as the expanded entries come into view, including supported and
pitted files. Each image stays with its original file when you change its variant label.

Use **Variant** to show all variants, **Unsupported**, **Supported**, or
**Unsupported (pitted)**. A grouped row matches every variant it contains. Filtering
also applies to select-all-matching and further scroll batches, and is retained when
switching workspaces. Thumbnails prefer the unsupported file; supported-only rows get
thumbnails showing their print supports. Pitted-only rows have no preview.

If an inferred group is wrong, choose **Split into separate rows**. Split rows receive
distinct names, which you can edit. To combine missed matches, select their rows,
optionally enter a **Grouped part name**, and choose **Group selected rows**. The
result shares the chosen source row's bundle, class and role; review those labels
after grouping. Different files assigned the same variant show **Assign variants**
and must be corrected or split before import. Separate rows targeting the same part
folder must be renamed or explicitly grouped. After a successful group,
selection clears so another pair can be grouped without including the completed
group, even when filters hide it. Select an existing group again if you intend to
merge it with another row.

Select rows or use the table header checkbox for every filtered result (including
rows not loaded yet) to apply bulk bundle,
class, role and name edits. The bulk **Supported / unsupported** field applies to
single-file rows; use the per-file selectors for grouped rows.
Use **Orient selection** for the usual rotation
grid, then **Save orientations** and **Back to table**. Orientation editing requires
an unsupported file. Mount authoring and region painting are disabled
throughout import. Switching to Ship Browser and back retains the import review and
its orientation previews.

**Import into library** moves the extracted models into
`<Bundle>/[<Class>/][weapons/[turrets/]]<Part Name>/`, naming variants
`unsupported.stl`, `supported.stl` or `unsupported-pitted.stl`. Reviewed orientations
and roles are saved with the imported parts. Identical repeated entries sharing a
destination are imported once. Different files targeting the same destination, invalid
folder names and existing library files stop the import with an error; correct the
reviewed names and retry. Existing files are never overwritten.

**Cancel import** discards the temporary review. Import edits do not change the library
until **Import into library** succeeds. The review is temporary and does not survive
an application restart. ZIP extraction is limited to 12 nested levels, 100,000 entries
and 64 GiB of expanded data; large bundles need corresponding temporary disk space.

## 3. Browsing your library

Use **Part Browser**, **Ship Browser**, and **Settings** in the masthead
to switch workspaces. Each keeps its own selection, filters, and mount-color setting
while Shipyard is running. Reloading restores the active workspace, selection and
mount-color setting; unsaved viewport pose edits still require Save before reloading.
Workspace buttons are briefly disabled while the page restores the active workspace
or completes a workspace switch.
Switching away and returning restores the model, including unsaved orientation-grid poses and
the editable assembly draft. Workspace navigation, filters and selection remain
available if the 3D view cannot load.

Part Browser opens as a table with part thumbnails showing saved region colors.
Mount summary lists plugs and socket capacity by accepted role. Regions shows Yes
when a part has saved non-Primary face assignments; clearing them returns it to No.
Returning from the part editor refreshes its thumbnail and these columns. Scroll down
to load more rows automatically, in batches of 50. **Load more** also works as a manual
retry. Changing a filter starts a new list; returning from an editor restores the
loaded rows and scroll position. Selection and workspace filters are briefly unavailable
while navigation restores the destination view. Filter by bundle/faction, class,
role, name or orientation status. Check rows to select them; selection remains when
filters hide rows. The checkbox in the table header selects or deselects every
filtered result, including unloaded rows, while retaining selections hidden by filters.
It shows a mixed state when some matches are selected. **Clear selection**, beside
the filter dropdowns, clears the whole selection. These controls also work in import review.
Choose a field, enter a value and click **Apply to selected** to
edit bundle/faction, class, role or name. Changing the row selection keeps your
chosen field, name operation and entered values, including edits made while the
selection is updating. For names, choose find-and-replace, prefix,
suffix or set-name. These labels survive rescans and do not rename source files.

For faction/bundle, class or role, open the bulk edit **Value** dropdown to choose a
saved value, or type to filter it. Choose **Add “value”** for a missing name. Arrow keys
and Enter select a choice; Escape closes the list. These controls work in both Part
Browser and import review. Typing or choosing a value does not save it: choose
**Apply to selected** to apply the edit. New
values become available in the current library's filters and editors after saving.
Values authored in import review remain staged until the import is committed. Role names become lowercase identifiers with spaces replaced
by hyphens (for example, **Sensor Array** becomes **sensor-array**). Custom roles are
available in the individual editor and socket acceptance choices, and participate in
assembly compatibility. Weapon sockets retain their turret-only restriction.

Part and ship thumbnails are saved in a disk cache and reused across reloads and app
restarts. Saved geometry, orientation, assembly, region, scheme and paint changes produce
updated previews when the list is shown again. Rendering shares a background job pool
with mesh preparation and mount recovery. By default, two background jobs run at a time
across the whole app, including import review. The cache removes older images as it fills; missing images
are recreated automatically. After replacing STL files externally, rescan the library.

To change background concurrency, add `:shipyard.jobs/pool {:threads 2 :queue-size 128}`
to your application `config.edn` and restart the app. Omitted or nil limits use these
defaults; they do not mean unlimited or follow CPU count. Both values must be positive
integers. A full queue leaves previews preparing until a later poll can admit the work.
Canceling an import stops only its jobs; browsing and thumbnails continue using the pool.

The **Thumbnail generation** indicator shows actual running and queued thumbnail
jobs across Part Browser, import review and Ship Browser. Cached image downloads,
unrequested rows and unavailable previews do not count as generation work. Thumbnails
are requested as rows enter view; opening a list does not generate the whole library.
Hover over **No preview** for the reason a row is skipped. Import review also previews
supported geometry; editing still requires unsupported geometry. Part and ship thumbnail cache lookups use small persistent references, so refreshing
a cached preview does not reread its region face assignments or custom paint.
Queued thumbnail jobs carry identifiers and version metadata; workers load geometry
and paint when they execute. Queue capacity bounds pending work, while worker count
controls simultaneous heavy rendering. A larger queue does not increase parallelism.

Double-click a row, or focus it and press Enter, to open the individual editor with
its floating inspector. **Back to table** restores filters, selection and scroll position.
The individual editor has no listing sidebar.

**About the role labels.** Shipyard guesses a part's role from its folder name.
The individual editor shows whether the role is inferred or manual. Roughly one part in ten is
labelled *unknown*, and some labels are simply wrong - a designer may sell a complete
escort in a folder named "Cyanide Prow Rapier", which reads as a prow but is a whole
ship. Roles control hull selection and which parts fit a socket's accepted roles.
Correct a mistaken role in the Part Browser before assembling that part; a manual
role takes precedence over the inferred label.

## 4. Viewing a part

Double-click a part row to load it.

| action | control |
|---|---|
| Rotate | drag with the left mouse button |
| Pan | drag with the right mouse button (Alt+right-drag while a paint brush is active) |
| Zoom | scroll wheel |

The first time you open a part, Shipyard prepares it for display, which takes a moment on
a large hull. The panel says *Preparing this part for display* while it works, and the
model appears when it is done - you can keep browsing in the meantime. After that it is
cached and opens instantly. The cache holds derived data only - deleting it costs nothing
but a little recomputation.

Meshes with identical source contents share cached preprocessing, even when they
belong to different parts. Their previews and saved orientations remain independent.

If a part cannot be prepared - a truncated download, a file that is not really an STL -
the panel says so and offers **Try again** rather than retrying silently. The rest of the
library keeps working; one bad file never takes the browser down with it.

## 5. Defining how parts connect

You will tell Shipyard how two parts mate by clicking the flat face where they meet: the
back of a weapon module, or a hull's weapon seat. One click gives Shipyard everything it
needs - where the part sits, which way it faces, and how it is rotated.

After a part has loaded, open the **Mounts** inspector tab and click the desired face.
The crosshair cursor indicates face picking. Opening **Regions** switches to the
region brush; opening **Part** returns to normal navigation. A newly selected part
opens its Part tab. Hold Alt while dragging to orbit without picking a face.
Saved mounts retain the selected faces. **Edit** reopens that exact selection; a linked
mirrored mount shows the reflected original selection, as it did before Save. Legacy
mounts recover the surface nearest their position.

Shipyard highlights the selected flat facet and draws its complete orientation frame:
the outward normal (`+Z`), in-plane twist reference (`+X`), and derived up direction (`+Y`).
Configured interfaces are always colored in the viewer when the part is loaded; the
detail panel shows a legend for the plug and socket types present on that part.

When the preview looks right, fill in the mount form:

- **Mount id** names this connection point within the part. It must start with a letter
  and may contain letters, numbers, dashes and underscores.
- **Kind** is `plug` for the back face of a module and `socket` for a place something
  attaches.
- **Accepts** is used only for sockets. Choose one profile from the dropdown: normally one
  role. Hulls also offer a named **Turret or antenna hardpoint** profile for a shared upper seat;
  weapon sockets offer only **Turret pit**, so antennae cannot be mounted to weapons.
  A plug has no acceptance profile: its compatibility comes from the part's saved role,
  such as a weapon plug fitting a hull socket that accepts weapons.
- **Capacity** is used for sockets whose selected face can hold more than one part.
  Human Navy Cruiser weapon sockets use capacity `2`.
- **Twist** rotates the `+X` and `+Y` directions around the fixed outward `+Z` normal
  before saving. Most mounts should remain at zero once the part orientation is correct.
- **Alignment axis** chooses **None**, **Horizontal (+X)** or **Vertical (+Y)** in the
  mount plane. A white line previews the choice; **Twist** rotates that line. Set an
  axis on both the socket and its mating plug to make their lines parallel in Assembly.
  The child rotates about the mating-face normal while staying on the mount. The line
  has no arrow, so Shipyard uses the smaller turn and treats opposite directions as
  already aligned. For a battery that needs a quarter turn, choose Horizontal on one
  mount and Vertical on the other; inspect the line previews before saving.
  Saved mounts reopen with their axis selected. Choose None and **Save changes** to
  clear it. If either mating mount is None, assembly uses its usual placement rule.
- **Mirror** creates a linked second socket by reflecting the picked frame across a symmetry
  plane. Human Navy Cruiser hulls use the X plane at offset `0`; change the plane or
  offset only when the part's centreline is different. The pair is configured, edited,
  and deleted together. When mirror is selected,
  Shipyard highlights the reflected face in blue before you save.

Without a paired alignment axis, Assembly turns a child around the global yaw (+Y) axis,
preserving the adjusted
model top on either side of a symmetric hull. For a vertical mount, where the face does
not determine yaw, the child faces forward with the part it is mounted to. A paired
alignment axis adds its explicit turn, and attached descendants follow the corrected pose.
- **Repeat classification** keeps the kind and accepted role ready for the next picked
  face. The next mount is still shown in the form and must be saved deliberately.

The **Interface colors** legend also shows the mount frame directions: **Normal (+Z)**,
**Twist reference (+X)** and **Up (+Y)**. These remain visible while authoring the first
mount, before any interface colors have been configured.

When capacity is above one, choose **Vertical — equal widths** or
**Horizontal — equal heights**. The selected face is divided in its own frame:
vertical cuts run along +Y, and horizontal cuts along +X. White lines show boundaries
and arrows show each section's center. Capacity and Twist changes update the preview.
Saved splits are stored with the mount in the database, including when mirrored. Older
capacity-only mounts need their face picked again to define the split.

When a flat face extends beyond the mounting area, enable **Trim faces (erase brush)**
in **Mount faces**. White triangle borders show the selection you opened. Left-drag
removes whole visible triangles under the circle; adjust **Brush radius** in screen
pixels. Alt+drag still orbits. **Undo erase** restores the previous stroke (up to 30),
and **Reset faces** restores the selection from when the form opened. Re-pick a face
to start over from its full planar surface.

Trimming changes only the draft until **Save mount** or **Save changes**. Keep at least
one triangle. The mount frame and capacity positions remain fixed; recess outlines
follow the retained triangles. Mirrored highlights and recess outlines reflect the
trimmed original selection. Cancel discards the edits and source STL bytes stay intact.
A stale source or an invalid boundary must be corrected before saving.

To make a printable variant, enable **Create pitted version** in the last section
of the mount form, just above its save buttons.
Plugs initially offer **Recess**; sockets offer **Pit**, and either choice can be changed.
The form shows **Diameter** for pits and **Border** for recesses. Switching the
cut type keeps the measurements available when you switch back.
All cut measurements are in millimetres. A pit uses **Depth** and **Diameter** and
is centered on the mount. For capacity above one, each section gets its own pit.
A recess uses **Depth** and **Border**: it follows the selected face's boundary,
leaving that border between the face edge and the recess. Border may be zero;
depth and diameter must be positive. Mirrored pairs mirror their cuts too.

Yellow wireframes show the cut opening, floor and walls through the model,
including its hidden side, whenever **Mount colors** is enabled, across all Part Browser
inspector tabs. Turning Mount colors off hides saved cut wireframes. Measurement changes
update the preview immediately. **Save mount** or **Save changes** rebuilds the
variant from the original source with all saved cuts. It writes a sibling file
with the **`-pitted.stl`** suffix: `unsupported.stl` produces
`unsupported-pitted.stl`. The original source stays intact, and the viewport
continues displaying it with the wireframes. Cut settings survive restart.
Saving overwrites an existing pitted variant with the newly generated result.
Disabling a cut or deleting its mount rebuilds the variant with the remaining
cuts; removing the last cut restores the original geometry in the pitted file.
Failed generation retains the prior file and saved mount definitions. Reduce a
border that consumes the face; if the source has changed, reopen the part and
pick its mount faces again before generating.
Generation requires a closed source mesh with consistently oriented faces;
repair an open or malformed STL in your mesh tool before generating cuts.

Part-level metadata is edited outside the mount picker. Use **Part metadata** in the
detail panel to edit **Name**, **Bundle / faction**, **Class** and **Role**. These use
the same fields and classification selectors as table row drawers and bulk editing;
each classification selector offers the same saved values as the table and lets you
add valid typed values. **Save metadata**
saves the four fields together. Invalid fields save no changes and keep your input
available to correct. Saved overrides replace inferred labels in browsing while
preserving the source identity, mounts, orientation, regions and loaded model. During
import, edit metadata in the review rows; the individual part editor is unavailable.

Use **Part orientation** to put the source mesh into Shipyard's canonical pose: `+Y` is
up, `+Z` is forward, and `+X` is starboard/right. Yaw rotates around Y, pitch around X,
and roll around Z. Those are fixed canonical axes: changing roll does not turn the axes
that yaw or pitch controls. A fixed widget in the viewport's lower-left
corner shows an asymmetric wireframe box and canonical axes: red is `+X`/pitch, green is
`+Y`/yaw, and blue is `+Z`/roll. Colored circular arrows show positive rotation using the
right-hand rule. The widget
follows the model's view as you orbit the camera while staying fixed in its corner.
Changes preview immediately; **Save orientation** stores the pose in the database,
while **Reset** returns it to the source STL orientation. Configure this before picking
mounts so each new mount derives its up direction consistently.
An empty angle field means `0` degrees; invalid or non-finite values are rejected.
Invalid orientation data in a bulk save is rejected before any part is written;
previous saved poses remain unchanged.

Click a part or import row to expand its drawer. The drawer shows a larger thumbnail and editable **Name**, **Bundle / faction**, **Class** and **Role** fields. Classification fields offer saved choices and accept new values.

The drawer also has **Yaw (Y)**, **Pitch (X)** and **Roll (Z)** fields in degrees. **Reset orientation** sets these fields to zero; it does not save. **Save part** saves that row's labels and edited angles together. Invalid fields save neither change, and untouched angles preserve the saved orientation or Unset state. Orientation controls require an unambiguous unsupported source; rows without one keep their label controls available and explain why orientation is disabled. Use **Orient selection** for bulk orientation changes.

Saving a row preserves bulk selection and other open drafts. Closing a drawer does not save. During import, saved row edits remain in the review until publication; Cancel discards them. Expand **files** inside an import drawer to assign original-file variants or split a group.

For a set of parts, open the **Part Browser** table. Filter by bundle,
class, role, name, or whether an orientation has already been saved, then select the
parts to edit and choose **Orient selection**. Shipyard lays the selected models out in
a grid. Each card shows its loading status. If a preview cannot download or decode,
choose **Retry preview** on that card; other loaded models keep their unsaved poses.
Choose a 1°, 15°, or 90° step; the toolbar's Pitch, Yaw, and Roll buttons turn
every model by that amount around the same fixed canonical axes. **Copy first** applies the first model's current pose to
the whole selection, and **Reset** restores every model to its last saved pose. Previewed
changes remain transient until **Save orientations** succeeds; a partial save identifies
the parts that failed without claiming they were saved. You can keep editing while a
save is pending: its response acknowledges the submitted pose, and newer edits remain
dirty with Save available. Reset restores the last successfully saved pose.
**Back to table** remains available while previews are preparing and retains your
selected parts. Your rotation step stays selected through preparation, returning to the
table and reopening the grid, or switching workspaces. Back to table discards unsaved
preview poses; the next grid starts from saved orientations.

**Back to table** refreshes saved angles and orientation status using the current
filters. Newly saved parts disappear from an Unset-only result, but stay selected.
Successful parts refresh after a partial save; failed parts retain their previous
saved metadata. Saving an identity pose also marks that part Saved.

**Save mount** creates a new id. If that id already exists, Shipyard reports it instead
of overwriting silently; use **Replace** only when you mean to update that mount. A part
can have multiple sockets, but only one plug. Existing mounts appear below the loaded
status with their picked or mirrored origin, plus capacity when it is greater than one.
Mirrored entries are shown as one pair and can be edited or deleted deliberately together.
Creating, editing, or deleting a mount keeps the Mounts tab open.
Choose **Edit** on the pair to restore both face previews, adjust its settings, and
**Save changes** to update both sockets. Mirroring stays enabled while editing a pair.

If the source mesh changes, the picked frame is malformed, a socket has no accepted role,
the mirror would overwrite an existing id, a centreline face has no mirrored counterpart,
or a mount id is not valid, Shipyard keeps the problem recoverable: pick the face again
or correct the field and save once more.

Mounts are saved in Shipyard's database. Back up the database along with your source
STLs; copying an STL folder alone does not copy its authored mounts.

## Assembly draft

Open **Ship Browser → New class**, then select a bundle, class, and hull in the
Assembly tab and choose **Start assembly**. The inspector lists every mount as a collapsible drawer, with only
the compatible parts for that mount. Select a part to assign it immediately; numbered
positions are separate assignments, so you can use the same printable part more than
once. Assigning a component with sockets reveals its nested drawers, such as turrets on
a weapon. Selecting or changing a component retains the inspector’s scroll position;
if the updated content is shorter, the position stops at its new bottom. Starting a
new hull or opening a different class starts at the top. **Clear** removes that
component and every nested assignment.

Each mount has a stable color in its drawer header and on the model; selected parts take
their mount's color. Split-capacity positions have distinct colors. Use **Mount colors**
to toggle these cues. Completed mount subtrees collapse by default and summarize their
selected descendants; incomplete subtrees stay open so their remaining choices are visible.

A compatible role, a single valid plug, and available unsupported geometry authorize
a choice within the hull's faction and class. Enable **Allow parts from other factions**
to include pieces from other factions. The setting is saved with the ship class and
retained when reopening or duplicating it. To turn it off, first clear any assigned
parts from other factions; a rejected toggle keeps your assembly intact.

Set a reusable piece's **Class** to **Universal** in the part or import classification
controls to use it on every hull class. Universal is a built-in value protected in
Settings. It bypasses the class match; using a Universal piece from another faction
still requires the toggle. Other class values must match the hull. A missing class
does not mean Universal.

Roles inferred from names or folders work in Assemble
and when saving, previewing, editing or duplicating a ship. You do not need to save each
role manually; use **Part metadata → Save metadata** when a role needs correcting. Mounts
still need to be authored.
Missing capacity splits require reauthoring.
Cold parts prepare in the background; failed preparation offers **Retry**. If the draft
changed elsewhere, review the refreshed panel before trying again. The choices remain
usable if the viewport bundle is unavailable. When the viewport is available, every
assignment appears in the same scene at its authored socket; repeated capacity positions
and nested turrets remain separate objects. Replacing or clearing a part removes that
object and everything beneath it before the new scene is shown. Switching workspaces shows the destination’s own model or empty state; returning to
Assemble restores the current in-memory draft.

Choose a hull, enter a **Class name**, and choose **Save class** to keep a named
assembly. Subsequent **Save changes** updates that class, including when you rename it.
Starting a new hull clears the saved identity so its next save creates a separate class.
If Assemble contains an unsaved class or changes, **Start assembly** first asks whether
to discard them. Choose **Cancel** to keep working, or **Discard and start assembly**
to replace the draft with the chosen hull.
You can save a hull alone or a partially filled assembly and finish it later. Empty
mounts also work when previewing, editing or duplicating a saved class. Unavailable or
incompatible assigned parts still need correcting before saving; the draft stays
available to correct and retry. Unsaved changes are lost when Shipyard stops.

Parts, shared layers, ship classes, named ships and schemes live in `$XDG_DATA_HOME/shipyard/database`
(normally `~/.local/share/shipyard/database`). Stop Shipyard and back up the whole
directory to keep your authored work. Keep a separate backup of the source STLs.
The viewport combines fleet palettes with each named ship’s custom materials and details. Use the Schemes inspector tab to create and edit fleet palettes. Thumbnails are not yet available.

## Ship classes and named ships

Open **Ship Browser** for a table of reusable ship classes. Scrolling automatically
loads more classes in batches of 50. Expanding a class loads its named ships, which
load further batches independently as you scroll. Row thumbnails show the
saved assembly; expanding a class loads previews of its named ships with their scheme
and custom colors. Previews use simplified lighting; open Assemble to inspect finishes. Filter by bundle/faction,
class, or a class or ship name. Double-click a class row (or focus it and press Enter)
to open **Assemble**. Expand **named ships** beneath a class to open one of its custom
painted hulls. The editor has **Assembly**, **Schemes**, and **Customize** tabs.

**Back to ships** restores the table's filters, scroll and expanded rows. **New class**
starts a fresh assembly; **Resume assembly** returns to your working draft. Assembly
controls now live in the floating inspector alongside scheme and paint controls.
**Mount colors** starts off and is shared by all three editor tabs.

Incomplete classes can be opened, edited and duplicated. **3 empty mounts** counts
unfilled mounts on the hull and attached parts; a hull-only class is valid too.
**Duplicate** opens an unsaved class named `<original name> - Copy`; saving creates a
separate identity and does not copy named ships. Opening another class or creating a
new draft asks before discarding unsaved changes. Reopening the current class resumes
its edits, and Back retains them.

**Delete** on a class row asks for confirmation and removes that class. Library parts
and other classes remain. Its named ships retain their custom paint but need the
original class restored before painting can continue. A draft editing that class
remains available as an unsaved copy; saving creates a new class identity.

## Reusable part paint regions

In **Part Browser**, use the viewport **View: Mount faces / View: Layer types**
toggle to switch between mount highlighting and region colors. It remembers its
setting separately from the other workspaces. Select a part and open its **Regions**
tab. Every face starts
as **Primary**. Choose **Secondary**, or enter a **New detail layer** name and
choose **Add layer**. The name immediately appears in the selectable layer list on
every other library part; choose it there without creating it again. Layers are shared library-wide: choose the same layer on multiple parts and set its
scheme material once in Ship Browser’s Schemes tab. A layer remains available even when no part uses it.

Select a layer in the list and click **Apply layer to entire part** to replace every
face assignment, including hidden/back faces, with that layer. The display switches
to Layer types to show the result. Choosing Primary
clears all assignments while retaining the part’s named layers.

Open **Regions**, click a colored layer row, and brush visible faces. The selected
row is highlighted and marked with a check. The brush is
active whenever this tab is visible; no extra toggle is needed. Release saves one complete stroke to the part’s regions in the database. Large
strokes are sent in the save request body without changing the page URL. Back to table
and workspace navigation are briefly disabled until each region save response arrives.
The brush restores the saved preview if a request is rejected and identifies the rejected
fields beneath the brush controls. A database save failure also restores the saved
preview and leaves the brush available; the message directs you to the server log,
which records the underlying exception.
Enable **Mirror painting** to paint or erase the matching opposite side in the
same stroke. Choose **Mirror plane** (YZ across X, XZ across Y, or XY across Z)
using the part's canonical axes, independent of camera orbit. Leave **Mirror
plane offset** blank to estimate the center from opposing surfaces, or enter its coordinate for an
off-center plane. A translucent guide shows the plane: red for X, green for Y,
blue for Z. It follows offset edits immediately and disappears when mirroring is
off or you leave Regions. You can paint through the guide. The brush's position, radius and path are
mirrored, selecting the first surface hit from the opposite viewpoint. This works
across different triangulation and small shape differences; the two sides need
not match exactly. Mirrored faces can be hidden from your current view. Rays that
miss the model leave it unchanged, and Facets mode does not paint through the
first surface it hits. Faces mode follows connected surfaces independently on each side.
The settings survive saves and layer edits, and reset when changing part, source
or orientation. Symmetry currently applies to Regions only.

The brush
starts from visible triangles. **Facets** paints the triangles touched by the brush;
**Faces** follows connected surfaces. Its **Angle tolerance** slider (0–90°, default
1°) sets the largest bend between neighboring triangles that the brush can cross.
Raise it to follow curved surfaces; larger creases and disconnected surfaces stop
the stroke. Expansion can include triangles outside the brush or behind other geometry. Right-drag erasing follows the same mode. Adjust its screen-space
radius; use Alt+drag to orbit. Region preview colors are chosen for separation from
the other types in your library, then stored so they stay stable when types are
renamed, added or deleted. Existing types receive new separated colors once when
upgrading from name-based colors. Schemes supply their final colors and finishes. Opening Regions
switches to Layer types. The display toggle can still show Mount faces independently.
Right-drag to erase assignments back to Primary without changing the selected layer;
the next left-drag uses that layer again. Choosing Primary also removes assignments.
Use any detail row’s **pencil** to edit its shared name, then **Save name** or
**Cancel**. You can rename or delete layers from any part, even if it does not use
them. Renaming preserves painted regions, scheme colors and preview swatches. Primary and Secondary have no edit or delete actions.
The row’s **×** asks for confirmation, then removes that type from every part in
the current library and returns its painted regions to Primary. It is available
even on parts that have not used that type. Primary and Secondary are permanent.
Deleted layers disappear from the list and scheme palettes. Recreating the same
name makes a new layer. **Reset regions** clears this part’s assignments after
confirmation while retaining shared layers.
Later edits live in the database. Back up the database along with your library;
copying a part folder alone does not carry current authoring. Rescanning the same
folder preserves authoring. Automatic relinking after moving part folders is not
currently supported.

Regions follow the library part into every instance and scheme. They survive restart
and rescan. If the source mesh changes, old regions are retained but do not apply;
open Regions and explicitly reset before authoring the replacement. A failed save
keeps the previous durable assignments and reports the error.

## Schemes and custom paint

Save a reusable **class** in the Assembly tab with **Save class**. Ship Browser’s class
rows expand to show named ships made from that class. Edit and Duplicate operate on
the reusable class.

Open **Schemes** in the floating inspector to create a fleet palette. Choose a scheme
or enter a name under **New scheme**. The layer list shows each layer’s current color.
Click a layer swatch to select it. Choose a hue, then drag in the spectrum to adjust
saturation and brightness, or type a six-digit **Hex color** such as `#d4af37`.
The spectrum also supports arrow keys (Shift makes larger steps).
The selected hue stays in place after saving even when the current color is gray or black.
**Save current color** adds it to **Saved colors**, shared across all schemes.
Click a saved swatch to reuse it, or its × button to remove it. Presets change only
color; **Metalness**, **Roughness**, **Glow** and optional **Paint name** remain separate controls.
Glow runs from 0 (off) to 1 and makes the selected color self-lit with a soft halo
and colored light on nearby parts. Nearby lighting is an approximation without cast shadows.
It is available for scheme layers, the detail brush as **Detail glow**.
Turning on **Mount colors** temporarily hides glow. Changes preview while dragging
and save on release. Select another class and choose Schemes to preview the same palette there. Preview
never creates a named ship or assigns a scheme to a class. Palette editing also works
without selecting a class. Turn off **Mount colors** to see paint.

Save any assembly changes, then open **Customize** for the selected class, enter a ship name, choose a fleet scheme and
click **Create ship**. This creates a named ship beneath that class’s row. A class
may have many named ships, each with its own scheme and custom paint. Select a named
ship from the expanded row to resume editing. The Ship Browser camera and controls
are shared across inspector tabs; there is no separate Customize workspace.

Customize lists named ships in a table with their class and scheme. **Edit** opens
that ship's detail brush; **Delete** asks for confirmation and removes only that
named ship and its paint. The Ship Browser's expanded named-ship rows provide the
same Edit and Delete controls. Canceled deletes leave the ship unchanged.

Use the detail brush to customize this ship over its region scheme. The brush uses
the same spectrum, hue slider, **Detail hex color** field and **Saved colors** as
Schemes. Picker changes affect the next stroke; releasing a stroke saves its details.
There is no Select tool or whole-instance, group or custom layer material editor.

Changing **Fleet scheme** keeps the ship’s custom paint. Palette edits in Schemes
appear beneath that paint on all ships using the palette. **Manage ship → Reset
custom paint** explicitly clears all custom face details after confirmation, keeping
the ship’s name, class and scheme. Rename and
Delete ship affect the named ship only. Deleting a scheme keeps ship references and
custom paint and shows a missing-scheme warning until another scheme is selected.

Named ships follow later class assembly changes. Custom paint stays attached to
matching part instances; replaced parts use the selected palette. Source-bound
details for a changed mesh remain saved but do not render until the original source
is restored or those details are cleared. Deleting a class retains its named ships’
paint, but restore that class before continuing to paint them.

Fleet ordering and fleet-default assignment are not yet available.

### Painting details

Turn off **Mount colors**, then open **Customize** to use the detail brush. Pick a detail
colour with the spectrum, hue slider, **Detail hex color** field or a saved swatch,
then set metalness, roughness and radius (2–100 screen pixels). One drag can touch
several parts. The inspector lists touched parts and face counts for the live drag;
parts identified behind the brush are skipped.

Left-drag to fill whole visible triangles under the circle. Hidden and back-facing
faces are never painted. Even a small brush fills a whole triangle. Alt+drag permits
orbiting. Colour and finish are captured together at the start
of each drag, so metallic details can sit beside matte faces. Finish controls initially
match the selected scheme’s Primary material. Changing them affects the next stroke,
not existing details.

For gold trim, choose a gold colour, metalness `1` and roughness around `0.2`.
Lower roughness gives sharper reflections. Right-drag erases details and restores
the underlying region scheme colour and finish without changing the
selected mode or paint material. The next left-drag can paint again. **Erase to base**
is also available for erasing with the left button. **Mount colors** temporarily replaces displayed
colours while keeping face finishes.

New faces are sent periodically while dragging, but nothing is durably saved until
you release. The whole drag is one undo step, including when it crosses parts.
**Undo** / **Redo** keep the last 20 strokes for the current named ship. Changing
ship or scheme resets that history. **Manage ship → Reset custom paint** clears all
its details after confirmation; this reset also clears history. Reset retained
incompatible details before painting a replacement source mesh.

Check **Details saved.** after release. If saving fails, every touched part returns to
its pre-stroke appearance; **Retry last stroke** retries the captured stroke. Leaving
Customize or canceling the drag discards its uncommitted preview. Face counts are
informational: strokes and saved layers have no face-count limits. Large meshes may
take longer to prepare their first detail layer.

Replaced parts/source files suppress incompatible details and show a warning in
Customize. Old masks remain saved until you use **Reset custom paint**.
After replacing a source file, reselect your library in Settings to rescan it before
reopening Customize. Restoring the original source restores its mask after the same rescan.

## Troubleshooting

**"Shipyard will not start, with an error about an unrecognised JVM option."** You are
on an older JDK. Install Java 25.

**"My library is empty."** Check the path is right, and that your folders match the
layout in section 2. Shipyard logs the root it is using at startup.

**"Could not save the setting: …"** Shipyard could not commit the folder change to
its database. The current library remains active and its saved selection is unchanged.
Check that the database location is writable and has free space, then press
**Use this folder** again.

**"A part I own is not listed."** Parts that ship only as `supported.stl` are hidden
from the regular Part Browser. Their files remain in the library and can be previewed
during import review.

**"A part will not open."** A small number of STL files in circulation are malformed or
in an unexpected format. Shipyard logs which file and carries on rather than failing
outright; the rest of your library is unaffected.

**"The 3D view is blank."** Your browser needs WebGL. Any current desktop browser has it,
but a remote session or a very old graphics driver may not. If Shipyard cannot get a 3D
context it says so in the browser console and disables the viewport only - browsing,
filtering and part details keep working, so nothing else on the page is lost.

## Getting help

Report problems at the [issue tracker](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/issues).
Include what you did, what happened, and the log output from the terminal.
