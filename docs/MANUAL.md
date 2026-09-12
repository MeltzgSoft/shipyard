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

The library panel lists every part Shipyard found. Filter by bundle, hull class, or role,
or search by name.

**About the role labels.** Shipyard guesses a part's role from its folder name, and shows
that guess in a lighter style because it is only a guess. Roughly one part in ten is
labelled *unknown*, and some labels are simply wrong - a designer may sell a complete
escort in a folder named "Cyanide Prow Rapier", which reads as a prow but is a whole
ship. Treat roles as a browsing aid. Nothing important depends on them.

## 4. Viewing a part

Select a part to load it.

| action | control |
|---|---|
| Rotate | drag with the left mouse button |
| Pan | drag with the right mouse button |
| Zoom | scroll wheel |

The first time you open a part, Shipyard prepares it for display, which takes a moment on
a large hull. The panel says *Preparing this part for display* while it works, and the
model appears when it is done - you can keep browsing in the meantime. After that it is
cached and opens instantly. The cache holds derived data only - deleting it costs nothing
but a little recomputation.

If a part cannot be prepared - a truncated download, a file that is not really an STL -
the panel says so and offers **Try again** rather than retrying silently. The rest of the
library keeps working; one bad file never takes the browser down with it.

## 5. Defining how parts connect

You will tell Shipyard how two parts mate by clicking the flat face where they meet: the
back of a weapon module, or a hull's weapon seat. One click gives Shipyard everything it
needs - where the part sits, which way it faces, and how it is rotated.

After a part has loaded, choose **Pick mount face** in the viewport's upper-left corner,
then click the desired face.
This is a global mode: it remains active as you move through the library, so you can
configure mounts on several parts without enabling it again. Choose **Done picking** to
turn it off.
Shipyard highlights the selected flat facet and draws its complete orientation frame:
the outward normal (`+Z`), in-plane twist reference (`+X`), and derived up direction (`+Y`).
Configured interfaces are always colored in the viewer when the part is loaded; the
detail panel shows a legend for the plug and socket types present on that part.

When the preview looks right, fill in the mount form:

- **Mount id** names this connection point within the part. It must start with a letter
  and may contain letters, numbers, dashes and underscores.
- **Kind** is `plug` for the back face of a module and `socket` for a place something
  attaches.
- **Accepts** is used only for sockets. It is a radio choice: normally choose one role. Hulls
  also offer a named **Turret or antenna hardpoint** profile for a shared upper seat;
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
Saved splits travel with the mount's sidecar, including when mirrored. Older
capacity-only mounts need their face picked again to define the split.

Part-level metadata is edited outside the mount picker. Use **Part metadata** in the
detail panel to set the role Shipyard should trust for that part from now on. It
replaces the inferred role shown by browsing.

Use **Part orientation** to put the source mesh into Shipyard's canonical pose: `+Y` is
up, `+Z` is forward, and `+X` is starboard/right. Yaw rotates around Y, pitch around X,
and roll around Z. Those are fixed canonical axes: changing roll does not turn the axes
that yaw or pitch controls. A fixed widget in the viewport's upper-right
corner shows an asymmetric wireframe box and canonical axes: red is `+X`/pitch, green is
`+Y`/yaw, and blue is `+Z`/roll. Colored circular arrows show positive rotation using the
right-hand rule. The widget
follows the model's view as you orbit the camera while staying fixed in its corner.
Changes preview immediately; **Save orientation** stores the pose in the part's sidecar,
while **Reset** returns it to the source STL orientation. Configure this before picking
mounts so each new mount derives its up direction consistently.
An empty angle field means `0` degrees; invalid or non-finite values are rejected.

**Save mount** creates a new id. If that id already exists, Shipyard reports it instead
of overwriting silently; use **Replace** only when you mean to update that mount. A part
can have multiple sockets, but only one plug. Existing mounts appear below the loaded
status with their picked or mirrored origin, plus capacity when it is greater than one.
Mirrored entries are shown as one pair and can be edited or deleted deliberately together.

If the source mesh changes, the picked frame is malformed, a socket has no accepted role,
the mirror would overwrite an existing id, a centreline face has no mirrored counterpart,
or a mount id is not valid, Shipyard keeps the problem recoverable: pick the face again
or correct the field and save once more.

This information is saved beside the part, in the same folder as its STL, so it survives
if you reorganise or move your library.

## Assembly draft

Choose **Assembly** in the header, or **Assemble this hull** on a loaded, manually
classified hull. Choose **Start assembly**, then assign compatible parts to its sockets.
Numbered positions are separate assignments; you can use the same printable part more
than once. Assigning a component with sockets reveals its nested slots, such as turrets
on a weapon. **Replace** or **Clear** removes that component's nested assignments.

Only authored roles, accepted socket roles, a single valid plug, available unsupported
geometry, and matching bundle/class authorize a choice. Inferred roles cannot do so.
Single-ship bundles are self-contained. Missing capacity splits require reauthoring.
Cold parts prepare in the background; failed preparation offers **Retry**. If the draft
changed elsewhere, review the refreshed panel before trying again. The choices remain
usable if the viewport bundle is unavailable. When the viewport is available, every
assignment appears in the same scene at its authored socket; repeated capacity positions
and nested turrets remain separate objects. Replacing or clearing a part removes that
object and everything beneath it before the new scene is shown. Returning to browse mode
clears the assembly scene; returning to Assembly restores the current in-memory draft.

The draft is in memory only. Resetting or stopping Shipyard loses it; named loadouts,
save/load, paint schemes and thumbnails are not available in M3.

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
