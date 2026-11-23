#!/usr/bin/env python3
"""Parametric catalog expansion generator (STEP 5 + whole-home expansion).

Generates app/src/main/assets/catalog_expansion.json by systematically
combining cabinet kinds x widths x heights x door styles x finishes, plus
appliances, worktop materials and floor materials — and, since the
whole-home expansion, bathroom cabinets/fixtures, living-room, bedroom and
office furniture, wardrobes, room-tagged flooring and wall finishes.

The schema is UNCHANGED (same 13 fields per SKU). Whole-home items use new
`kind` values ("FURNITURE", "FIXTURE", "WALL") and new cabinetKind values
("bath-vanity", "bath-tall", "wardrobe"); the room a SKU belongs to is
derivable from kind + cabinetKind + the SKU id structure (mirrored by
`room_of` below and by CatalogExpansion.roomTypeOf in Kotlin). Flooring and
wall finishes are tagged per room type via a room token in the SKU id
(e.g. FLR-PORCELAIN-600X600-BATHROOM, WAL-PAINT-KITCHEN-IVORY); the original
untagged floor SKUs remain and count as ALL_ROOMS.

Python 3 standard library only. Prices are ESTIMATE placeholders produced by a
deterministic formula (base by kind + per-width increment, finish multiplier,
rounded to the nearest 100 USD cents) — NOT supplier quotes. `supplier` is
always null.

Run:  python3 scripts/generate_catalog.py
The script self-validates after writing: it re-loads the JSON, asserts the
schema keys on a sample spanning every kind, asserts the total SKU count is
>= 3000 (with the original kitchen counts intact), asserts per-room coverage,
and prints counts per kind / room plus the total.
"""

import json
import os
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_PATH = os.path.normpath(
    os.path.join(HERE, "..", "app", "src", "main", "assets", "catalog_expansion.json")
)

SCHEMA_VERSION = 1
MIN_TOTAL_SKUS = 3000
# Original kitchen counts that must stay intact (whole-home work is additive).
EXPECTED_KITCHEN_COUNTS = {"CABINET_KITCHEN": 1614, "APPLIANCE": 57, "WORKTOP": 18}
ROOMS = ["KITCHEN", "BATHROOM", "LIVING", "BEDROOM", "OFFICE"]

# ---------------------------------------------------------------------------
# Finish / material property table.
# heat / humidity drive both the resistance flags and the region rule:
# a SKU is tagged "MENA" when its finish/material is heat- or
# humidity-resistant (properties that matter in hot, humid MENA kitchens),
# otherwise "GLOBAL".
# ---------------------------------------------------------------------------
FINISHES = {
    # id:             (price multiplier, heat_resistant, humidity_resistant)
    "oak":            (1.15, False, False),
    "acrylic-white":  (1.25, False, True),
    "hpl-grey":       (1.00, True,  True),
    "hpl-green":      (1.00, True,  True),
    "walnut":         (1.30, False, False),
    "matte-black":    (1.20, False, False),
}

DOOR_STYLES = ["slab", "shaker", "handleless", "glass"]
# Glass fronts only make sense on display-type units.
GLASS_KINDS = {"wall", "wall-lift", "corner-wall", "tall"}
ALL_STYLES = DOOR_STYLES
PLAIN_STYLES = ["slab", "shaker", "handleless"]

# ---------------------------------------------------------------------------
# Cabinet kinds: widths (mm), (height, depth) variants (mm), base price
# (USD cents) and per-100mm-of-width price increment (USD cents).
# Standard metric module widths are a subset of 300/400/450/500/600/800/900/
# 1000, plus 700 for the tall fridge housing (matches the starter catalog's
# tall-fridge-700).
# ---------------------------------------------------------------------------
CABINET_KINDS = {
    # kind:           (widths,                                   [(height, depth)],        base_cents, per_100mm)
    "base":           ([300, 400, 450, 500, 600, 800, 900, 1000], [(860, 560)],              11_000, 1_500),
    "base-sink":      ([500, 600, 800, 900, 1000],                [(860, 560)],              13_000, 1_400),
    "base-hob":       ([500, 600, 800, 900, 1000],                [(860, 560)],              12_000, 1_400),
    "base-drawer":    ([300, 400, 450, 500, 600, 800, 900],       [(860, 560)],              14_000, 1_800),
    "wall":           ([300, 400, 450, 500, 600, 800, 900, 1000], [(720, 320), (900, 320)],  7_000, 1_100),
    "wall-lift":      ([600, 800, 900, 1000],                     [(720, 320), (900, 320)], 11_000, 1_500),
    "tall":           ([300, 400, 450, 500, 600],                 [(2100, 580), (2360, 580)], 22_000, 2_500),
    "tall-oven":      ([600],                                     [(2100, 580), (2360, 580)], 26_000, 2_500),
    "tall-fridge":    ([600, 700, 900],                           [(2100, 650), (2360, 650)], 27_000, 2_500),
    "corner-base":    ([900, 1000],                               [(860, 560)],              20_000, 1_800),
    "corner-wall":    ([900, 1000],                               [(720, 320), (900, 320)],  13_000, 1_500),
    "peninsula-base": ([600, 800, 900, 1000],                     [(860, 560)],              15_000, 1_600),
}

DOOR_STYLE_ADD_CENTS = {"slab": 0, "shaker": 2_000, "handleless": 1_500, "glass": 3_500}
TALL_HEIGHT_ADD_CENTS = 3_000   # extra for the 2360 mm height variant
HIGH_WALL_ADD_CENTS = 1_500     # extra for the 900 mm wall height variant

# ---------------------------------------------------------------------------
# Appliances: (widths, height, depth, base_cents, per_100mm) per type.
# ---------------------------------------------------------------------------
APPLIANCE_TYPES = {
    "sink":        ([400, 500, 600, 800], 200, 460, 9_000, 1_200),
    "hob":         ([300, 600, 900],      50,  520, 25_000, 3_000),
    "oven":        ([600, 900],          595, 567, 45_000, 4_000),
    "fridge":      ([600, 700, 900],     1900, 650, 80_000, 6_000),
    "dishwasher":  ([450, 600],          820, 550, 40_000, 3_500),
    "hood":        ([600, 900],          500, 500, 22_000, 2_500),
    "microwave":   ([600],               320, 350, 15_000, 2_000),
    "wine-cooler": ([300, 600],          820, 550, 55_000, 5_000),
}
APPLIANCE_FINISHES = {
    # id:          (price multiplier, heat_resistant, humidity_resistant)
    "stainless": (1.15, True,  True),
    "black":     (1.05, True,  False),
    "white":     (1.00, False, False),
}

# ---------------------------------------------------------------------------
# Worktops: priced per linear metre; the SKU represents a 1000 x 600 mm run,
# height = slab thickness.
# ---------------------------------------------------------------------------
WORKTOP_MATERIALS = {
    # id:             (price/linear-metre cents, heat_resistant, humidity_resistant)
    "quartz":        (32_000, True,  True),
    "granite":       (28_000, True,  True),
    "marble-look":   (24_000, False, True),
    "laminate":      (9_000,  False, False),
    "solid-surface": (26_000, True,  True),
    "stainless":     (30_000, True,  True),
}
WORKTOP_THICKNESSES_MM = [20, 30, 40]
WORKTOP_DEPTH_MM = 600

# ---------------------------------------------------------------------------
# Floors: format variants are (width, depth, thickness) in mm; the SKU
# represents one format pack of that material.
# ---------------------------------------------------------------------------
FLOOR_MATERIALS = {
    # id:             (base_cents, heat_resistant, humidity_resistant, [(w, d, t), ...])
    "porcelain":     (6_000, True,  True,  [(600, 600, 10), (800, 800, 10), (600, 1200, 10)]),
    "oak-laminate":  (4_000, False, False, [(190, 1200, 8), (220, 1800, 12), (190, 1800, 12)]),
    "vinyl":         (3_500, False, True,  [(180, 1220, 5), (230, 1500, 5), (300, 600, 5)]),
    "microcement":   (7_500, False, True,  [(1000, 1000, 3), (1000, 2000, 3), (500, 500, 3)]),
}


def round_to_100(cents: float) -> int:
    """Round a price estimate to the nearest 100 USD cents."""
    return int((cents + 50) // 100) * 100


def region_for(heat_resistant: bool, humidity_resistant: bool) -> str:
    return "MENA" if (heat_resistant or humidity_resistant) else "GLOBAL"


def sku_record(sku, kind, cabinet_kind, width, height, depth, door_style,
               finish_id, price_cents, heat, humidity):
    return {
        "sku": sku,
        "kind": kind,
        "cabinetKind": cabinet_kind,
        "widthMm": width,
        "heightMm": height,
        "depthMm": depth,
        "doorStyle": door_style,
        "finishId": finish_id,
        "priceUsdCentsEstimate": price_cents,
        "region": region_for(heat, humidity),
        "heatResistant": heat,
        "humidityResistant": humidity,
        "supplier": None,
    }


def generate_cabinets():
    skus = []
    for kind, (widths, dims, base_cents, per_100mm) in CABINET_KINDS.items():
        styles = ALL_STYLES if kind in GLASS_KINDS else PLAIN_STYLES
        for width in widths:
            for height, depth in dims:
                for style in styles:
                    for finish_id, (mult, heat, humidity) in FINISHES.items():
                        raw = base_cents + ((width - 300) // 100) * per_100mm
                        raw += DOOR_STYLE_ADD_CENTS[style]
                        if height == 2360:
                            raw += TALL_HEIGHT_ADD_CENTS
                        elif height == 900:
                            raw += HIGH_WALL_ADD_CENTS
                        price = round_to_100(raw * mult)
                        sku = "CAB-{}-{}-H{}-{}-{}".format(
                            kind.upper(), width, height,
                            style.upper(), finish_id.upper())
                        skus.append(sku_record(
                            sku, "CABINET", kind, width, height, depth,
                            style, finish_id, price, heat, humidity))
    return skus


def generate_appliances():
    skus = []
    for atype, (widths, height, depth, base_cents, per_100mm) in APPLIANCE_TYPES.items():
        for width in widths:
            for finish_id, (mult, heat, humidity) in APPLIANCE_FINISHES.items():
                raw = base_cents + ((width - 300) // 100) * per_100mm
                price = round_to_100(raw * mult)
                sku = "APP-{}-{}-{}".format(atype.upper(), width, finish_id.upper())
                skus.append(sku_record(
                    sku, "APPLIANCE", None, width, height, depth,
                    None, finish_id, price, heat, humidity))
    return skus


def generate_worktops():
    skus = []
    for material_id, (per_metre_cents, heat, humidity) in WORKTOP_MATERIALS.items():
        for thickness in WORKTOP_THICKNESSES_MM:
            # Thicker slab = proportionally more material: +15% per extra 10 mm.
            raw = per_metre_cents * (1.0 + 0.15 * ((thickness - 20) // 10))
            price = round_to_100(raw)
            sku = "WT-{}-T{}".format(material_id.upper(), thickness)
            skus.append(sku_record(
                sku, "WORKTOP", None, 1000, thickness, WORKTOP_DEPTH_MM,
                None, material_id, price, heat, humidity))
    return skus


def generate_floors():
    skus = []
    for material_id, (base_cents, heat, humidity, formats) in FLOOR_MATERIALS.items():
        for width, depth, thickness in formats:
            area_factor = (width * depth) / (600 * 600)
            price = round_to_100(base_cents * area_factor)
            sku = "FLR-{}-{}X{}".format(material_id.upper(), width, depth)
            skus.append(sku_record(
                sku, "FLOOR", None, width, thickness, depth,
                None, material_id, price, heat, humidity))
    return skus


# ---------------------------------------------------------------------------
# Whole-home expansion (Workstream C). Same schema, same pricing pattern
# (base + per-100mm-of-width increment, finish multiplier, round to 100c),
# same region rule (heat- or humidity-resistant finish/material -> MENA).
# Items are parametric dimensioned entries, NOT 3D models.
# ---------------------------------------------------------------------------

# Bathroom cabinets reuse the kitchen FINISHES / PLAIN_STYLES parametric grid.
BATH_CABINET_KINDS = {
    # kind:           (widths,                          [(height, depth)],   base_cents, per_100mm)
    "bath-vanity":    ([450, 600, 750, 900, 1200, 1500], [(550, 520), (860, 520)], 16_000, 1_800),
    "bath-tall":      ([300, 400, 600],                  [(1800, 350), (2100, 350)], 18_000, 2_000),
}

# Wardrobes: door count is a function of width and is encoded in the SKU id
# (…-D<doors>-…); front style still uses the standard doorStyle values.
WARDROBE_WIDTH_DOORS = {800: 2, 1000: 2, 1200: 3, 1500: 3, 1800: 4, 2000: 4, 2400: 6}
WARDROBE_DIMS = [(2000, 600), (2100, 600), (2360, 600)]  # (height, depth)
WARDROBE_BASE_CENTS = 30_000
WARDROBE_PER_100MM = 2_800

# Bathroom fixtures (mirrors, shower, bath, WC, bidet). Bathroom service
# finishes are humidity-resistant by construction, hence MENA-tagged.
FIXTURE_FINISHES = {
    # id:               (price multiplier, heat_resistant, humidity_resistant)
    "chrome":         (1.10, True,  True),
    "matte-black":    (1.05, False, True),
    "brushed-brass":  (1.25, False, True),
    "white-ceramic":  (1.00, False, True),
}
TRAY_FINISHES = {
    "white-acrylic":  (1.00, False, True),
    "stone-resin":    (1.30, False, True),
}
BATH_FINISHES = {
    "acrylic-white":  (1.00, False, True),
    "enamel-steel":   (1.15, True,  True),
}
CERAMIC_FINISHES = {
    "white-ceramic":       (1.00, False, True),
    "matte-black-ceramic": (1.20, False, True),
}

# Upholstery finishes for seating (no heat/humidity resistance -> GLOBAL).
SOFA_FINISHES = {
    "fabric-grey":   (1.00, False, False),
    "fabric-sand":   (1.00, False, False),
    "velvet-green":  (1.20, False, False),
    "boucle-white":  (1.15, False, False),
    "leather-tan":   (1.35, False, False),
    "leather-black": (1.30, False, False),
}
BED_FINISHES = {
    "oak":           (1.15, False, False),
    "walnut":        (1.30, False, False),
    "fabric-grey":   (1.05, False, False),
    "fabric-sand":   (1.05, False, False),
    "boucle-white":  (1.15, False, False),
    "white-painted": (1.00, False, False),
}
METAL_FINISHES = {
    "metal-grey":  (1.00, False, False),
    "metal-black": (1.05, False, False),
    "metal-white": (1.00, False, False),
}
CHAIR_FINISHES = {
    "fabric-grey":   (1.00, False, False),
    "fabric-black":  (1.00, False, False),
    "mesh-black":    (0.95, False, False),
    "leather-black": (1.30, False, False),
}

# Extra floor materials that only appear as room-tagged SKUs.
ROOM_FLOOR_MATERIALS = {
    # id:               (base_cents, heat, humidity, [(w, d, t), ...])
    "terrazzo":       (8_500, True,  True,  [(600, 600, 12), (800, 800, 12), (600, 1200, 12)]),
    "herringbone-oak": (7_000, False, False, [(120, 600, 10), (150, 700, 10), (190, 900, 12)]),
    "marble-tile":    (11_000, True,  True,  [(600, 600, 10), (600, 1200, 10), (800, 800, 10)]),
    "cork":           (4_500, False, True,  [(300, 600, 6), (600, 600, 6), (900, 300, 6)]),
}

# Wall finishes: the SKU is a 1000 x 1000 mm (1 m2) coverage unit;
# heightMm carries the material thickness, as with FLOOR SKUs.
WALL_MATERIALS = {
    # id:            (base_cents_per_m2, heat, humidity, thickness_mm, [variant/finish ids])
    "paint":       (2_200, False, True,  1,  ["ivory", "white", "sand", "sage", "terracotta", "charcoal"]),
    "limewash":    (3_800, False, True,  2,  ["ivory", "sand", "clay", "sage"]),
    "tile":        (6_500, True,  True,  8,  ["white-gloss", "white-matte", "sage-gloss", "black-matte"]),
    "wallpaper":   (4_200, False, False, 1,  ["botanical", "geometric", "stripe", "plain-linen"]),
    "panel":       (7_800, False, False, 12, ["oak", "walnut", "white-painted"]),
    "microcement": (7_200, False, True,  3,  ["grey", "sand", "white"]),
}

NEW_CABINET_KINDS = set(BATH_CABINET_KINDS) | {"wardrobe"}


def _priced(base_cents, per_100mm, width, mult, extra_cents=0):
    raw = base_cents + ((width - 300) // 100) * per_100mm + extra_cents
    return round_to_100(raw * mult)


def generate_bathroom_cabinets():
    skus = []
    for kind, (widths, dims, base_cents, per_100mm) in BATH_CABINET_KINDS.items():
        for width in widths:
            for height, depth in dims:
                for style in PLAIN_STYLES:
                    for finish_id, (mult, heat, humidity) in FINISHES.items():
                        price = _priced(base_cents, per_100mm, width, mult,
                                        DOOR_STYLE_ADD_CENTS[style])
                        sku = "CAB-{}-{}-H{}-{}-{}".format(
                            kind.upper(), width, height, style.upper(), finish_id.upper())
                        skus.append(sku_record(
                            sku, "CABINET", kind, width, height, depth,
                            style, finish_id, price, heat, humidity))
    return skus


def generate_wardrobes():
    skus = []
    for width, doors in sorted(WARDROBE_WIDTH_DOORS.items()):
        for height, depth in WARDROBE_DIMS:
            for style in PLAIN_STYLES:
                for finish_id, (mult, heat, humidity) in FINISHES.items():
                    extra = DOOR_STYLE_ADD_CENTS[style]
                    if height == 2360:
                        extra += TALL_HEIGHT_ADD_CENTS
                    price = _priced(WARDROBE_BASE_CENTS, WARDROBE_PER_100MM,
                                    width, mult, extra)
                    sku = "CAB-WARDROBE-{}-H{}-D{}-{}-{}".format(
                        width, height, doors, style.upper(), finish_id.upper())
                    skus.append(sku_record(
                        sku, "CABINET", "wardrobe", width, height, depth,
                        style, finish_id, price, heat, humidity))
    return skus


def _fixture(sku, width, height, depth, finish_id, price, heat, humidity):
    return sku_record(sku, "FIXTURE", None, width, height, depth,
                      None, finish_id, price, heat, humidity)


def generate_bathroom_fixtures():
    skus = []
    # Mirrors: widths x heights (depth = 30 mm frame/glass build).
    for width in (450, 600, 750, 900, 1200):
        for height in (700, 900):
            for finish_id, (mult, heat, humidity) in FIXTURE_FINISHES.items():
                price = _priced(8_000, 800, width, mult)
                skus.append(_fixture("FIX-MIRROR-{}-H{}-{}".format(
                    width, height, finish_id.upper()),
                    width, height, 30, finish_id, price, heat, humidity))
    # Shower enclosures: width = front width, (height, depth) variants.
    for width in (800, 900, 1000, 1200):
        for height, depth in ((1950, 800), (2100, 900)):
            for finish_id, (mult, heat, humidity) in FIXTURE_FINISHES.items():
                price = _priced(45_000, 3_000, width, mult)
                skus.append(_fixture("FIX-SHOWER-{}-H{}-{}".format(
                    width, height, finish_id.upper()),
                    width, height, depth, finish_id, price, heat, humidity))
    # Shower trays: (width, depth, thickness) formats.
    for width, depth, thick in ((800, 800, 40), (900, 900, 40), (1000, 1000, 40), (1200, 800, 40)):
        for finish_id, (mult, heat, humidity) in TRAY_FINISHES.items():
            price = _priced(18_000, 1_500, width, mult)
            skus.append(_fixture("FIX-TRAY-{}X{}-{}".format(
                width, depth, finish_id.upper()),
                width, thick, depth, finish_id, price, heat, humidity))
    # Baths: width = length, (height, depth) variants.
    for width in (1500, 1600, 1700, 1800):
        for height, depth in ((550, 700), (600, 750)):
            for finish_id, (mult, heat, humidity) in BATH_FINISHES.items():
                price = _priced(55_000, 4_000, width, mult)
                skus.append(_fixture("FIX-BATH-{}-H{}-{}".format(
                    width, height, finish_id.upper()),
                    width, height, depth, finish_id, price, heat, humidity))
    # WCs (floor-standing + wall-hung heights) and bidets.
    for width in (360, 380, 400):
        for height in (320, 400):
            for finish_id, (mult, heat, humidity) in CERAMIC_FINISHES.items():
                price = _priced(28_000, 2_000, width, mult)
                skus.append(_fixture("FIX-WC-{}-H{}-{}".format(
                    width, height, finish_id.upper()),
                    width, height, 550, finish_id, price, heat, humidity))
        for finish_id, (mult, heat, humidity) in CERAMIC_FINISHES.items():
            price = _priced(22_000, 1_800, width, mult)
            skus.append(_fixture("FIX-BIDET-{}-{}".format(width, finish_id.upper()),
                                 width, 400, 550, finish_id, price, heat, humidity))
    return skus


def _furniture(sku, width, height, depth, finish_id, price, heat, humidity):
    return sku_record(sku, "FURNITURE", None, width, height, depth,
                      None, finish_id, price, heat, humidity)


def _furniture_grid(prefix, variants, finishes, base_cents, per_100mm):
    """variants: list of (width, height, depth). Emits one SKU per variant x finish."""
    skus = []
    for width, height, depth in variants:
        for finish_id, (mult, heat, humidity) in finishes.items():
            price = _priced(base_cents, per_100mm, width, mult)
            sku = "FRN-{}-{}-H{}-D{}-{}".format(
                prefix, width, height, depth, finish_id.upper())
            skus.append(_furniture(sku, width, height, depth,
                                   finish_id, price, heat, humidity))
    return skus


def generate_living_room():
    skus = []
    # Sofas by seat count: width encodes seats (1/2/3/4 -> 900/1500/2100/2800).
    for seats, width in ((1, 900), (2, 1500), (3, 2100), (4, 2800)):
        for finish_id, (mult, heat, humidity) in SOFA_FINISHES.items():
            price = _priced(60_000, 9_000, width, mult)
            skus.append(_furniture("FRN-SOFA-{}-S{}-{}".format(
                width, seats, finish_id.upper()),
                width, 850, 950, finish_id, price, heat, humidity))
    skus += _furniture_grid("SOFACORNER", [(w, 850, 1600) for w in (2400, 2800, 3200)],
                            SOFA_FINISHES, 110_000, 8_000)
    skus += _furniture_grid("ARMCHAIR", [(800, 850, 900)], SOFA_FINISHES, 35_000, 4_000)
    skus += _furniture_grid("TVUNIT", [(w, 450, 400) for w in (1200, 1500, 1800, 2000, 2400)],
                            FINISHES, 30_000, 3_500)
    skus += _furniture_grid("SHELF", [(w, h, 300) for w in (600, 800, 1000, 1200)
                                      for h in (1200, 1800, 2100)],
                            FINISHES, 18_000, 2_200)
    skus += _furniture_grid("COFFEE", [(900, 350, 600), (1200, 350, 700), (800, 400, 800)],
                            FINISHES, 14_000, 1_800)
    skus += _furniture_grid("SIDEBOARD", [(w, 800, 450) for w in (1200, 1500, 1800, 2000)],
                            FINISHES, 32_000, 3_500)
    return skus


def generate_bedroom_furniture():
    skus = []
    # Beds in standard sizes: width x length (depth); two headboard heights.
    for width, length in ((900, 1900), (1200, 1900), (1400, 1900),
                          (1600, 2000), (1800, 2000), (2000, 2000)):
        for height in (900, 1100):
            for finish_id, (mult, heat, humidity) in BED_FINISHES.items():
                price = _priced(45_000, 5_000, width, mult)
                skus.append(_furniture("FRN-BED-{}X{}-H{}-{}".format(
                    width, length, height, finish_id.upper()),
                    width, height, length, finish_id, price, heat, humidity))
    skus += _furniture_grid("BEDSIDE", [(w, 550, 400) for w in (400, 500, 600)],
                            FINISHES, 12_000, 1_500)
    skus += _furniture_grid("DRESSER", [(w, 800, 500) for w in (800, 1200, 1600)],
                            FINISHES, 28_000, 3_200)
    return skus


def generate_office():
    skus = []
    skus += _furniture_grid("DESK", [(w, 750, d) for w in (1000, 1200, 1400, 1600, 1800)
                                     for d in (600, 800)],
                            FINISHES, 24_000, 2_800)
    skus += _furniture_grid("BOOKCASE", [(w, h, 300) for w in (600, 800, 1000)
                                         for h in (1200, 1800)],
                            FINISHES, 16_000, 2_000)
    skus += _furniture_grid("FILING", [(w, h, 600) for w in (400, 600)
                                       for h in (600, 1200)],
                            METAL_FINISHES, 15_000, 1_800)
    skus += _furniture_grid("CHAIR", [(650, 1000, 650)], CHAIR_FINISHES, 22_000, 2_000)
    return skus


def generate_room_floors():
    """Every floor material/format tagged once per room type."""
    skus = []
    all_materials = dict(FLOOR_MATERIALS)
    all_materials.update(ROOM_FLOOR_MATERIALS)
    for material_id, (base_cents, heat, humidity, formats) in all_materials.items():
        for width, depth, thickness in formats:
            area_factor = (width * depth) / (600 * 600)
            for room in ROOMS:
                price = round_to_100(base_cents * area_factor)
                sku = "FLR-{}-{}X{}-{}".format(material_id.upper(), width, depth, room)
                skus.append(sku_record(
                    sku, "FLOOR", None, width, thickness, depth,
                    None, material_id, price, heat, humidity))
    return skus


def generate_wall_finishes():
    skus = []
    for material_id, (base_cents, heat, humidity, thickness, variants) in WALL_MATERIALS.items():
        for room in ROOMS:
            for variant in variants:
                sku = "WAL-{}-{}-{}".format(material_id.upper(), room, variant.upper())
                skus.append(sku_record(
                    sku, "WALL", None, 1000, thickness, 1000,
                    None, variant, base_cents, heat, humidity))
    return skus


# Room derivation — mirrors CatalogExpansion.roomTypeOf in Kotlin.
_LIVING_SUBTYPES = {"SOFA", "SOFACORNER", "ARMCHAIR", "TVUNIT", "SHELF", "COFFEE", "SIDEBOARD"}
_BEDROOM_SUBTYPES = {"BED", "BEDSIDE", "DRESSER"}
_OFFICE_SUBTYPES = {"DESK", "BOOKCASE", "FILING", "CHAIR"}


def room_of(rec):
    kind = rec["kind"]
    if kind == "CABINET":
        if rec["cabinetKind"] in BATH_CABINET_KINDS:
            return "BATHROOM"
        if rec["cabinetKind"] == "wardrobe":
            return "BEDROOM"
        return "KITCHEN"
    if kind in ("APPLIANCE", "WORKTOP"):
        return "KITCHEN"
    if kind == "FIXTURE":
        return "BATHROOM"
    if kind == "FURNITURE":
        subtype = rec["sku"].split("-")[1]
        if subtype in _LIVING_SUBTYPES:
            return "LIVING"
        if subtype in _BEDROOM_SUBTYPES:
            return "BEDROOM"
        if subtype in _OFFICE_SUBTYPES:
            return "OFFICE"
        raise ValueError("unknown furniture subtype: %s" % rec["sku"])
    if kind == "FLOOR":
        last = rec["sku"].rsplit("-", 1)[-1]
        return last if last in ROOMS else "ALL_ROOMS"
    if kind == "WALL":
        return rec["sku"].split("-")[2]
    raise ValueError("unknown kind: %s" % kind)


def generate():
    skus = (generate_cabinets() + generate_appliances()
            + generate_worktops() + generate_floors()
            + generate_bathroom_cabinets() + generate_wardrobes()
            + generate_bathroom_fixtures() + generate_living_room()
            + generate_bedroom_furniture() + generate_office()
            + generate_room_floors() + generate_wall_finishes())
    skus.sort(key=lambda s: s["sku"])
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedBy": "scripts/generate_catalog.py",
        "priceDisclaimer": "ESTIMATE placeholder prices in USD cents — NOT supplier quotes",
        "skus": skus,
    }


# ---------------------------------------------------------------------------
# Self-validation
# ---------------------------------------------------------------------------
SKU_KEYS = {
    "sku", "kind", "cabinetKind", "widthMm", "heightMm", "depthMm",
    "doorStyle", "finishId", "priceUsdCentsEstimate", "region",
    "heatResistant", "humidityResistant", "supplier",
}


def validate(doc):
    assert doc["schemaVersion"] == SCHEMA_VERSION, "schemaVersion mismatch"
    assert doc["generatedBy"] == "scripts/generate_catalog.py"
    assert "priceDisclaimer" in doc and doc["priceDisclaimer"]
    skus = doc["skus"]
    assert isinstance(skus, list) and skus, "skus must be a non-empty list"

    # Schema keys asserted on EVERY record (cheap, and catches generator
    # drift anywhere in the list, not just in a sample).
    all_kinds = ("CABINET", "APPLIANCE", "WORKTOP", "FLOOR",
                 "FURNITURE", "FIXTURE", "WALL")
    all_cabinet_kinds = set(CABINET_KINDS) | NEW_CABINET_KINDS
    for rec in skus:
        assert set(rec.keys()) == SKU_KEYS, "bad keys on %s: %s" % (rec.get("sku"), sorted(rec.keys()))
        assert rec["kind"] in all_kinds
        assert rec["region"] in ("GLOBAL", "MENA")
        assert rec["region"] == region_for(rec["heatResistant"], rec["humidityResistant"]), \
            "region rule violated on %s" % rec["sku"]
        assert isinstance(rec["priceUsdCentsEstimate"], int) and rec["priceUsdCentsEstimate"] > 0
        assert rec["priceUsdCentsEstimate"] % 100 == 0, "price not rounded to 100c: %s" % rec["sku"]
        assert rec["supplier"] is None
        assert rec["widthMm"] > 0 and rec["heightMm"] > 0 and rec["depthMm"] > 0
        if rec["kind"] == "CABINET":
            assert rec["cabinetKind"] in all_cabinet_kinds, rec["sku"]
            assert rec["doorStyle"] in DOOR_STYLES, rec["sku"]
        else:
            assert rec["cabinetKind"] is None and rec["doorStyle"] is None, rec["sku"]
        # Every SKU must resolve to a room (mirrors the Kotlin derivation).
        assert room_of(rec) in ROOMS + ["ALL_ROOMS"], rec["sku"]

    ids = [s["sku"] for s in skus]
    assert len(ids) == len(set(ids)), "duplicate SKU ids"
    total = len(skus)
    assert total >= MIN_TOTAL_SKUS, "total %d < required %d" % (total, MIN_TOTAL_SKUS)

    # Original kitchen counts must stay intact.
    kitchen_cabinets = sum(1 for s in skus if s["kind"] == "CABINET" and room_of(s) == "KITCHEN")
    assert kitchen_cabinets == EXPECTED_KITCHEN_COUNTS["CABINET_KITCHEN"], kitchen_cabinets
    for kind, expected in (("APPLIANCE", EXPECTED_KITCHEN_COUNTS["APPLIANCE"]),
                           ("WORKTOP", EXPECTED_KITCHEN_COUNTS["WORKTOP"])):
        actual = sum(1 for s in skus if s["kind"] == kind)
        assert actual == expected, "%s %d != %d" % (kind, actual, expected)

    # Whole-home coverage minimums.
    by_kind = Counter(s["kind"] for s in skus)
    assert by_kind["FURNITURE"] >= 250, by_kind["FURNITURE"]
    assert by_kind["FIXTURE"] >= 50, by_kind["FIXTURE"]
    assert by_kind["WALL"] >= 100, by_kind["WALL"]
    assert by_kind["FLOOR"] >= 100, by_kind["FLOOR"]
    by_room = Counter(room_of(s) for s in skus)
    for room in ROOMS:
        assert by_room[room] >= 100, "room %s only %d SKUs" % (room, by_room[room])
    return skus


def main():
    doc = generate()
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)
        fh.write("\n")

    # Re-load from disk and validate what was actually written.
    with open(OUT_PATH, "r", encoding="utf-8") as fh:
        loaded = json.load(fh)
    skus = validate(loaded)

    by_kind = Counter(s["kind"] for s in skus)
    by_cabinet_kind = Counter(s["cabinetKind"] for s in skus if s["kind"] == "CABINET")
    by_region = Counter(s["region"] for s in skus)
    by_room = Counter(room_of(s) for s in skus)

    print("Wrote %s" % OUT_PATH)
    print("Counts per kind:")
    for kind in ("CABINET", "APPLIANCE", "WORKTOP", "FLOOR",
                 "FURNITURE", "FIXTURE", "WALL"):
        print("  %-10s %d" % (kind, by_kind[kind]))
    print("Cabinets per cabinetKind:")
    for kind in list(CABINET_KINDS) + sorted(NEW_CABINET_KINDS):
        print("  %-16s %d" % (kind, by_cabinet_kind[kind]))
    print("Counts per room type:")
    for room in ROOMS + ["ALL_ROOMS"]:
        print("  %-10s %d" % (room, by_room[room]))
    print("Counts per region:")
    for region in ("GLOBAL", "MENA"):
        print("  %-8s %d" % (region, by_region[region]))
    print("TOTAL SKUs: %d (minimum required: %d)" % (len(skus), MIN_TOTAL_SKUS))
    print("Validation: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
