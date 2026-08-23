# Shipyard User Manual

Shipyard lets you assemble a Battlefleet Gothic ship from the STL parts you already own,
look at it from every angle, and decide what to print before you commit resin to it.

> **This manual tracks the software.** Sections marked *Not yet built* describe features
> that are specified but not implemented. Each becomes real as its milestone lands, and
> this document is updated in the same change - never afterwards.

**Current state:** M1 in progress. You can browse your library and view individual parts.
Assembling a whole ship is M3.

---

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

The default location is `~/Documents/3D_models/BFG`. To use another, either set it once:

```bash
mkdir -p ~/.config/shipyard
cat > ~/.config/shipyard/config.edn <<'END'
{:shipyard.library/index {:root "/path/to/your/models"}}
END
```

or override per run:

```bash
SHIPYARD_LIBRARY=/path/to/your/models java -jar shipyard-…jar
```

The environment variable wins if you use both.

### How your library should be organised

Shipyard expects **one folder per part**, holding that part's variants:

```
<Bundle>/[<Class>/][weapons/]<Part Name>/
    unsupported.stl              <- what Shipyard displays
    unsupported-pitted.stl       <- preferred if present (magnet pits cut)
    supported.stl                <- print scaffolding; never read
```

A folder counts as a part if it holds at least one of those three files. Folders named
`other` are skipped, so slicer projects and README files can live alongside your models
without confusing anything.

If a part only ships as `supported.stl`, it still appears in the library - greyed out,
with the reason - rather than silently vanishing. You should be able to see everything
you own, even the parts Shipyard cannot render.

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

*Not yet built - milestone M2.*

You will tell Shipyard how two parts mate by clicking the flat face where they meet: the
back of a weapon module, or a hull's weapon seat. One click gives Shipyard everything it
needs - where the part sits, which way it faces, and how it is rotated.

Hulls are symmetrical, so defining a port-side mount will offer to create the matching
starboard one automatically.

This information is saved beside the part, in the same folder as its STL, so it survives
if you reorganise or move your library.

## 6. Assembling a ship

*Not yet built - milestone M3.*

Choose a hull, then fill its slots - prow, bridge, weapons - from the parts that fit.
The ship assembles on screen as you go.

## 7. Saving loadouts

*Not yet built - milestone M4.*

Save a configuration under a name, reopen it later, and duplicate it to try variations.

## 8. Paint schemes

*Not yet built - milestone M5.*

Assign colours per part and see them on the model under realistic lighting, so you can
judge a scheme before opening a pot of paint.

## 9. Fleets

*Not yet built - milestone M6.*

Group saved ships into a fleet, with a shared paint scheme and per-ship overrides.

---

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
but a remote session or a very old graphics driver may not.

## Getting help

Report problems at the [issue tracker](https://forgejo.tail943578.ts.net/MeltzgSoft/shipyard/issues).
Include what you did, what happened, and the log output from the terminal.
