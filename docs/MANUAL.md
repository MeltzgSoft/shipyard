# Shipyard User Manual

Shipyard lets you browse and preview Battlefleet Gothic STL parts you already own and
define the mount frames that describe how those parts connect.

This manual covers supported behavior. Planned work lives in the
[Forgejo milestones](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/milestones).

## 1. Installing

You need **Java 25**. Check with `java -version`. Shipyard is developed and tested
against 25 and relies on JVM options that do not exist in earlier releases.

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

1. Type or paste the folder that holds your bundles - the one whose sub-folders are
   `Human Navy Fleet Bundle` and the like. `~` works.
2. Press **Use this folder**.

Shipyard scans it immediately and the parts appear; there is nothing to restart. The
folder is remembered, so every run after this one starts with your library already
loaded.

To change it later, open **Library folder** at the top of the library panel. If the
path is wrong - a typo, or a drive that is not mounted - Shipyard says so and keeps
using the folder it already had.

Your choice is stored in `$XDG_CONFIG_HOME/shipyard/library.edn` (usually
`~/.config/shipyard/library.edn`). You can write it by hand if you prefer:

```bash
mkdir -p ~/.config/shipyard
echo '{:root "/path/to/your/models"}' > ~/.config/shipyard/library.edn
```

### How your library should be organised

Shipyard expects **one folder per part**, holding that part's variants:

```
<Bundle>/[<Class>/][weapons/]<Part Name>/
    unsupported.stl              <- what Shipyard displays
    unsupported-pitted.stl       <- recorded, but never displayed (magnet pits cut)
    supported.stl                <- print scaffolding; never read
```

A folder counts as a part if it holds at least one of those three files. Folders named
`other` are skipped, so slicer projects and README files can live alongside your models
without confusing anything.

If a part lacks `unsupported.stl` - because it only ships as a pitted or supported STL -
it still appears in the library, greyed out with the reason, rather than silently
vanishing. You should be able to see everything you own, even parts Shipyard cannot
render without showing pits or print scaffolding.

## 3. Browsing your library

Use **Part Browser** and **Ship Browser** in the masthead
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
Returning from the part editor refreshes its thumbnail and these columns. Tables show 50 rows per page; use Previous and Next to navigate. Selection and workspace filters are briefly unavailable while navigation restores the destination view. Selected parts stay selected across pages, and changing a filter returns to the first page. Filter by bundle/faction, class,
role, name or orientation status. Check rows to select them; selection remains when
filters hide rows. Choose a field, enter a value and click **Apply to selected** to
edit bundle/faction, class, role or name. Changing the row selection keeps your
chosen field, name operation and entered values, including edits made while the
selection is updating. For names, choose find-and-replace, prefix,
suffix or set-name. These labels survive rescans and do not rename source files.

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
- **Mirror** creates a linked second socket by reflecting the picked frame across a symmetry
  plane. Human Navy Cruiser hulls use the X plane at offset `0`; change the plane or
  offset only when the part's centreline is different. The pair is configured, edited,
  and deleted together. When mirror is selected,
  Shipyard highlights the reflected face in blue before you save.

Assembly turns a child only around the global yaw (+Y) axis, preserving the adjusted
model top on either side of a symmetric hull. For a vertical mount, where the face does
not determine yaw, the child faces forward with the part it is mounted to.
- **Repeat classification** keeps the kind and accepted role ready for the next picked
  face. The next mount is still shown in the form and must be saved deliberately.

When capacity is above one, choose **Vertical — equal widths** or
**Horizontal — equal heights**. The selected face is divided in its own frame:
vertical cuts run along +Y, and horizontal cuts along +X. White lines show boundaries
and arrows show each section's center. Capacity and Twist changes update the preview.
Saved splits are stored with the mount in the database, including when mirrored. Older
capacity-only mounts need their face picked again to define the split.

Part-level metadata is edited outside the mount picker. Use **Part metadata** in the
detail panel to set the role Shipyard should trust for that part from now on. It
replaces the inferred role shown by browsing.

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

If the source mesh changes, the picked frame is malformed, a socket has no accepted role,
the mirror would overwrite an existing id, a centreline face has no mirrored counterpart,
or a mount id is not valid, Shipyard keeps the problem recoverable: pick the face again
or correct the field and save once more.

This information is saved beside the part, in the same folder as its STL, so it survives
if you reorganise or move your library.

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

A compatible role, a single valid plug, available unsupported geometry, and matching
bundle/class authorize a choice. Roles inferred from names or folders work in Assemble
and when saving, previewing, editing or duplicating a ship. You do not need to save each
role manually; use **Part metadata → Save role** when a role needs correcting. Mounts
still need to be authored.
Single-ship bundles are self-contained. Missing capacity splits require reauthoring.
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

Open **Ship Browser** for a table of reusable ship classes. Use Previous and Next for large libraries. Expanding a class loads its named ships, with separate page controls when needed. Row thumbnails show the
saved assembly; expanding a class loads previews of its named ships with their scheme
and custom colors. Previews use simplified lighting; open Assemble to inspect finishes. Filter by bundle/faction,
class, or a class or ship name. Double-click a class row (or focus it and press Enter)
to open **Assemble**. Expand **named ships** beneath a class to open one of its custom
painted hulls. The editor has **Assembly**, **Schemes**, and **Paint** tabs.

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
strokes are sent in the save request body without changing the page URL. The brush
restores the saved preview if a request is rejected and identifies the rejected
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
Deleted layers disappear from the list and Paint defaults. Recreating the same
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
It is available for scheme layers, custom ship materials, and the detail brush as **Detail glow**.
Turning on **Mount colors** temporarily hides glow. Changes preview while dragging
and save on release. Select another class and choose Schemes to preview the same palette there. Preview
never creates a named ship or assigns a scheme to a class. Palette editing also works
without selecting a class. Turn off **Mount colors** to see paint.

Save any assembly changes, then open **Paint** for the selected class, enter a ship name, choose a fleet scheme and
click **Create ship**. This creates a named ship beneath that class’s row. A class
may have many named ships, each with its own scheme and custom paint. Select a named
ship from the expanded row to resume editing. The Ship Browser camera and controls
are shared across inspector tabs; there is no separate Paint workspace.

Use instance rows, custom layer overrides, groups and the detail brush to customize
this ship. Materials preview locally and save on release; **Save material** retries a
failed save. If you change a material while a save is pending, its response keeps
your newer preview on screen until you save it. Custom paint affects only this named ship. Instance materials override
its layer palette; face details override individual faces. **Use inherited material**
removes the selected instance, group or layer override. Check instance rows and use
**Group selection** to create a group; **Manage group** changes name, membership,
priority or deletes the group. The first matching group with a material wins, beneath
an instance override. Repeated copies of one weapon can have different materials.

Changing **Fleet scheme** keeps the ship’s custom paint. Palette edits in Schemes
appear beneath that paint on all ships using the palette. **Manage ship → Reset
custom paint** explicitly clears all custom layers, groups, instance materials and
details after confirmation, keeping the ship’s name, class and scheme. Rename and
Delete ship affect the named ship only. Deleting a scheme keeps ship references and
custom paint and shows a missing-scheme warning until another scheme is selected.

Named ships follow later class assembly changes. Custom paint stays attached to
matching part instances; replaced parts use the selected palette. Source-bound
details for a changed mesh remain saved but do not render until the original source
is restored or those details are cleared. Deleting a class retains its named ships’
paint, but restore that class before continuing to paint them.

Fleet ordering and fleet-default assignment are not yet available.

### Painting details

Turn off **Mount colors**, then choose **Brush** in the Paint inspector. Pick a detail
colour, metalness, roughness and radius (2–100 screen pixels). **Cross instances** is on by default: one
drag can touch several parts. Turn it off to paint only the instance selected in
**Select** mode. The inspector lists touched parts and face counts for the live drag;
parts identified behind the brush are skipped.

Left-drag to fill whole visible triangles under the circle. Hidden and back-facing
faces are never painted. Even a small brush fills a whole triangle. Alt+drag or the
**Select** tool permits orbiting. Colour and finish are captured together at the start
of each drag, so metallic details can sit beside matte faces. Finish controls initially
match the selected target's effective material. Changing them affects the next stroke,
not existing details.

For gold trim, choose a gold colour, metalness `1` and roughness around `0.2`.
Lower roughness gives sharper reflections. Right-drag erases details and restores
the underlying instance/group/layer colour and finish without changing the
selected mode or paint material. The next left-drag can paint again. **Erase to base**
is also available for erasing with the left button. **Mount colors** temporarily replaces displayed
colours while keeping face finishes.

New faces are sent periodically while dragging, but nothing is durably saved until
you release. The whole drag is one undo step, including when it crosses parts.
**Undo** / **Redo** keep the last 20 strokes for the current target selection. Changing
target or scheme resets that history. **Clear instance details** removes the selected
instance's entire layer after confirmation and can also be undone. Choose an instance
in Select mode to clear it. Details belong only to the selected named ship.

Check **Details saved.** after release. If saving fails, every touched part returns to
its pre-stroke appearance; **Retry last stroke** retries the captured stroke. Leaving
Paint or canceling the drag discards its uncommitted preview. Face counts are
informational: strokes and saved layers have no face-count limits. Large meshes may
take longer to prepare their first detail layer.

Replaced parts/source files suppress incompatible details and show a warning in
Paint. The old mask remains saved until you explicitly clear that instance's details.
After replacing a source file, reselect your library in Settings to rescan it before
reopening Paint. Restoring the original source restores its mask after the same rescan.

## Troubleshooting

**"Shipyard will not start, with an error about an unrecognised JVM option."** You are
on an older JDK. Install Java 25.

**"My library is empty."** Check the path is right, and that your folders match the
layout in section 2. Shipyard logs the root it is using at startup.

**"A part I own is not listed."** It probably has no `unsupported.stl`. Parts that ship
only as `supported.stl` appear greyed out - look for them rather than assuming they are
missing.

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
