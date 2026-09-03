# M2 Human Navy Cruiser Proof

Issue #63 uses the Human Navy Cruiser as the first real target for the M2 mount
workflow. The STL files are user-owned data and are not committed to this repository, so
the proof is an on-demand command plus this captured run.

## Reproduction

Materialize a temporary Shipyard-style Cruiser library, then run:

```bash
clojure -M:m2-human-navy-cruiser-proof \
  --root /tmp/human-navy-cruiser-copy \
  --out m2-human-navy-cruiser-proof.edn
```

The command writes `shipyard.edn` sidecars into the root it is given. Run it against a
copy, not against your only library.

The checked run used `/tmp/shipyard-real/human-navy-library`, materialized from the
user-owned archive `Human Navy Bundle.zip`, nested path `Human Navy/HN Cruiser.zip`.

## Result

The proof authored 15 mounts across 8 real Cruiser parts, wrote them through
`shipyard.catalog.db/save-authoring!`, then loaded a fresh catalog and confirmed every
manual role and mount count came back from sidecars.

| total | count |
|---|---:|
| parts | 8 |
| mounts | 15 |
| plugs | 7 |
| sockets | 8 |
| mirrored sockets | 1 |
| turret sockets | 3 |
| ambiguous roll cases | 0 |

The workflow covers every M2 mount kind and origin used by Cruiser authoring:
picked plugs, picked sockets, a mirrored port/starboard weapon socket, repeated turret
sockets on the hull, and a turret socket authored on the Weapon Battery component. It
does not exercise M3 assembly or compatibility filtering.

## Geometry

Facet grouping used the M2 defaults:

| option | value |
|---|---:|
| facet angle | 1.0 deg |
| facet plane epsilon | 0.01 mm |

Part sizes from the real STL files:

| part | triangles | bbox min | bbox max |
|---|---:|---|---|
| Hull | 132892 | `[-19.061 -18.87 5.0]` | `[19.061 18.87 86.76601]` |
| Classic Ram Prow | 77296 | `[399.99802 0.0 0.0]` | `[425.88202 15.386001 22.872002]` |
| Bridge | 11064 | `[-7.5842376 -9.631819 5.0000005]` | `[6.8340783 9.525915 20.76741]` |
| Antenna 1 | 1986 | `[385.0 20.0 0.0]` | `[388.42477 36.585815 3.4247572]` |
| Lance Battery | 3286 | `[155.0 24.998001 0.0]` | `[172.324 33.624 8.918]` |
| Weapon Battery | 2370 | `[230.0 25.0 0.0]` | `[247.052 33.624 4.2629995]` |
| Lance Turret | 1162 | `[255.0 25.0 0.0]` | `[259.008 30.271202 3.7607956]` |
| Dorsal Turret | 1194 | `[265.0 25.0 0.0]` | `[269.01642 27.939209 3.5824013]` |

Mount face spans are recorded as the two bbox extents perpendicular to the saved mount
axis:

| part | mount | origin | bbox face span |
|---|---|---|---|
| Hull | prow | picked | `[38.122 37.74]` |
| Hull | bridge | picked | `[38.122 81.766]` |
| Hull | antenna | picked | `[38.122 81.766]` |
| Hull | port-1 | picked | `[37.74 81.766]` |
| Hull | starboard-1 | mirrored | `[37.74 81.766]` |
| Hull | turret-1 | picked | `[38.122 81.766]` |
| Hull | turret-2 | picked | `[38.122 81.766]` |
| Classic Ram Prow | plug | picked | `[25.884 15.386]` |
| Bridge | plug | picked | `[14.418 19.158]` |
| Antenna 1 | plug | picked | `[3.425 16.586]` |
| Lance Battery | plug | picked | `[8.626 8.918]` |
| Weapon Battery | plug | picked | `[8.624 4.263]` |
| Weapon Battery | turret-pit | picked | `[17.052 8.624]` |
| Lance Turret | plug | picked | `[4.008 5.271]` |
| Dorsal Turret | plug | picked | `[4.016 2.939]` |

## Timing

The checked run wrote sidecars and updated the in-memory catalog in a few milliseconds
per part. The hull was the largest authoring write at 37.358 ms total, or 5.337 ms per
mount. Other parts ranged from 2.409 ms to 4.436 ms per mount.
