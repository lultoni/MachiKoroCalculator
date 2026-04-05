#!/usr/bin/env python3
"""
Generate a clean card reference JSON from scraped wiki data + RULES.md knowledge.

Reads scripts/scraped_cards.json and produces scripts/scraped_cards_reference.json with:
- All base game cards with complete, verified data
- All expansion cards for future reference
- German names where available (from projects.json)
"""

import json

# Load scraped data
with open("scripts/scraped_cards.json") as f:
    scraped = json.load(f)

# Load existing projects.json for German names
with open("src/resources/jsons/projects.json") as f:
    projects = json.load(f)

# Build lookup from English name to project ID
en_to_id: dict[str, str] = {}
for pid, pdata in projects.items():
    en_name = pdata.get("name_en", "")
    en_to_id[en_name] = pid

# Manual Shopping Mall data (not in wiki infobox)
MANUAL_CARDS = {
    "Shopping Mall": {
        "type": "Landmark",
        "color": "yellow",
        "cost": "10",
        "cost_num": 10,
        "activation": "N/A",
        "activation_numbers": [],
        "effect": "Each of your Bread and Coffee Cup establishments earn +1 coin.",
        "icon": "Landmark",
        "expansion": "Base Game",
    }
}

def normalize_activation(act_str: str) -> list[int]:
    """Parse activation string into list of numbers."""
    import re
    if not act_str or act_str.upper() in ("N/A", "NONE", "?"):
        return []
    nums: list[int] = []
    # Handle "2 to 3", "9-10", "11 to 12", "12 to 14"
    act_str = act_str.replace(" to ", "-")
    for part in act_str.replace(",", " ").split():
        m = re.match(r"(\d+)\s*[-–]\s*(\d+)", part)
        if m:
            nums.extend(range(int(m.group(1)), int(m.group(2)) + 1))
        elif part.isdigit():
            nums.append(int(part))
    return sorted(set(nums))


def build_reference():
    cards_by_name: dict[str, dict] = {}

    # Process scraped cards
    for card in scraped["cards"]:
        name = card["name_en"]
        entry = {
            "name_en": name,
            "wiki_page": card.get("wiki_page", name),
        }

        # Apply manual overrides first
        if name in MANUAL_CARDS:
            entry.update(MANUAL_CARDS[name])

        # Map fields from scraped data (don't override manual)
        for key in ("type", "icon", "cost", "activation", "effect", "expansion", "color"):
            if key not in entry and key in card:
                entry[key] = card[key]

        # Activation numbers
        if "activation_numbers" not in entry:
            if "activation" in entry:
                entry["activation_numbers"] = normalize_activation(entry["activation"])
            elif "activation_numbers" in card:
                entry["activation_numbers"] = card["activation_numbers"]
            else:
                entry["activation_numbers"] = []

        # Cost number
        if "cost_num" not in entry and "cost" in entry:
            import re
            m = re.match(r"(\d+)", str(entry["cost"]))
            if m:
                entry["cost_num"] = int(m.group(1))

        # Color from type
        if "color" not in entry and "type" in entry:
            type_map = {
                "primary industry": "blue",
                "secondary industry": "green",
                "restaurants": "red",
                "major establishment": "purple",
                "landmark": "yellow",
            }
            entry["color"] = type_map.get(entry["type"].lower(), "unknown")

        # Categories
        entry["categories"] = card.get("categories", [])

        # Expansion from categories
        if "expansion" not in entry:
            for cat in entry["categories"]:
                if "Base Game" in cat:
                    entry["expansion"] = "Base Game"
                    break
                elif "Harbor" in cat:
                    entry["expansion"] = "Harbor Expansion"
                    break
                elif "Millionaire" in cat:
                    entry["expansion"] = "Millionaire's Row"
                    break

        # Is base game?
        entry["is_base_game"] = (
            entry.get("expansion") == "Base Game"
            or "Base Game" in entry.get("categories", [])
        )

        # German name from projects.json
        pid = en_to_id.get(name)
        if pid:
            entry["project_id"] = pid
            entry["name_de"] = pid  # German ID is the German name
            pdata = projects[pid]
            entry["description_de"] = pdata.get("description", "")
            entry["category_game"] = pdata.get("category", "")
            entry["json_color"] = pdata.get("color", "")
            entry["json_cost"] = pdata.get("cost", 0)
            entry["json_dice"] = pdata.get("dice_activation", [])
            entry["json_is_landmark"] = pdata.get("is_grossprojekt", False)

        # Gameplay text from wiki
        if "gameplay_text" in card:
            entry["gameplay_text"] = card["gameplay_text"]
        if "strategy_text" in card:
            entry["strategy_text"] = card["strategy_text"]

        # Raw infobox for reference
        entry["raw_infobox"] = card.get("raw_infobox", {})

        cards_by_name[name] = entry

    # Sort: base game first, then by activation
    base_cards = [c for c in cards_by_name.values() if c.get("is_base_game")]
    expansion_cards = [c for c in cards_by_name.values() if not c.get("is_base_game")]

    base_cards.sort(key=lambda c: (c.get("activation_numbers") or [999])[0] if c.get("activation_numbers") else 999)
    expansion_cards.sort(key=lambda c: (c.get("expansion", "Z"), (c.get("activation_numbers") or [999])[0] if c.get("activation_numbers") else 999))

    output = {
        "generated_at": __import__("time").strftime("%Y-%m-%d %H:%M:%S"),
        "source": "https://machi-koro.fandom.com/wiki/ + RULES.md + projects.json",
        "base_game_count": len(base_cards),
        "expansion_count": len(expansion_cards),
        "total": len(cards_by_name),
        "base_game_cards": base_cards,
        "expansion_cards": expansion_cards,
    }

    outpath = "scripts/scraped_cards_reference.json"
    with open(outpath, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f"=== Reference file: {outpath} ===")
    print(f"Base game: {len(base_cards)} cards")
    print(f"Expansions: {len(expansion_cards)} cards")
    print()

    print("--- BASE GAME CARDS ---")
    print(f"{'Name':30s} {'Color':8s} {'Cost':>5s} {'Act':12s} {'Icon':10s} {'Effect'}")
    print("-" * 120)
    for c in base_cards:
        name = c["name_en"]
        color = c.get("color", "?")
        cost = str(c.get("cost_num", c.get("cost", "?")))
        act = c.get("activation", "?")
        icon = c.get("icon", "?")
        effect = (c.get("effect") or "?")[:50]
        pid = c.get("project_id", "—")
        match = "OK" if pid != "—" else "MISSING"
        print(f"{name:30s} {color:8s} {cost:>5s} {act:12s} {icon:10s} {effect:50s} [{match}: {pid}]")

    print()
    print("--- EXPANSION CARDS ---")
    for c in expansion_cards:
        name = c["name_en"]
        exp = c.get("expansion", "?")
        color = c.get("color", "?")
        cost = str(c.get("cost_num", c.get("cost", "?")))
        act = c.get("activation", "?")
        effect = (c.get("effect") or "?")[:40]
        print(f"  [{exp:20s}] {name:30s} {color:8s} cost={cost:>3s}  act={act:12s} {effect}")


if __name__ == "__main__":
    build_reference()
