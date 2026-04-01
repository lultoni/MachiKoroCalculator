#!/usr/bin/env python3
"""
Scrape Machi Koro card data from the Fandom wiki using the MediaWiki API.

Uses api.php?action=parse to get wikitext (no JS rendering needed).
Outputs structured JSON to scraped_cards.json.
"""

import json
import re
import time
import sys
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from urllib.parse import quote

BASE_API = "https://machi-koro.fandom.com/api.php"
UA = "MachiKoroScraper/1.0"


def fetch_wikitext(page_title: str) -> str:
    """Fetch raw wikitext for a page via the MediaWiki API."""
    encoded = quote(page_title.replace(" ", "_"), safe="")
    url = f"{BASE_API}?action=parse&page={encoded}&prop=wikitext&format=json"
    req = Request(url, headers={"User-Agent": UA})
    try:
        with urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read())
            return data.get("parse", {}).get("wikitext", {}).get("*", "")
    except HTTPError as e:
        print(f"  ERROR: HTTP {e.code} for {page_title}", file=sys.stderr)
        return ""


def fetch_category_members(category: str) -> list[str]:
    """Fetch all page titles in a category."""
    titles: list[str] = []
    url = f"{BASE_API}?action=query&list=categorymembers&cmtitle=Category:{quote(category)}&cmlimit=500&format=json"
    req = Request(url, headers={"User-Agent": UA})
    try:
        with urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read())
            for m in data.get("query", {}).get("categorymembers", []):
                titles.append(m["title"])
    except HTTPError as e:
        print(f"  ERROR: HTTP {e.code} for category {category}", file=sys.stderr)
    return titles


def parse_infobox(wikitext: str) -> dict[str, str]:
    """Extract key=value pairs from an Infobox template, handling nested braces."""
    # Find the start of the infobox
    start_match = re.search(r"\{\{Infobox\s+\w+\s*\n", wikitext, re.IGNORECASE)
    if not start_match:
        return {}

    # Walk forward counting brace depth to find the matching close
    pos = start_match.start()
    depth = 0
    i = pos
    while i < len(wikitext) - 1:
        if wikitext[i:i+2] == "{{":
            depth += 1
            i += 2
        elif wikitext[i:i+2] == "}}":
            depth -= 1
            if depth == 0:
                break
            i += 2
        else:
            i += 1

    infobox_content = wikitext[start_match.end():i]

    fields: dict[str, str] = {}
    for line in infobox_content.split("\n"):
        line = line.strip()
        if line.startswith("|"):
            parts = line[1:].split("=", 1)
            if len(parts) == 2:
                key = parts[0].strip().lower()
                val = parts[1].strip()
                # Clean up wiki markup: {{Wheat}} -> Wheat
                val = re.sub(r"\{\{([^}|]+?)(?:\|[^}]*)?\}\}", r"\1", val)
                val = re.sub(r"\[\[([^|\]]*\|)?([^\]]*)\]\]", r"\2", val)
                val = val.strip()
                fields[key] = val
    return fields


def extract_sections(wikitext: str) -> dict[str, str]:
    """Extract named sections from wikitext."""
    sections: dict[str, str] = {}
    current = "intro"
    current_lines: list[str] = []

    for line in wikitext.split("\n"):
        header_match = re.match(r"^(={2,})\s*(.+?)\s*\1\s*$", line)
        if header_match:
            if current_lines:
                text = "\n".join(current_lines).strip()
                if text:
                    sections[current] = text
            current = header_match.group(2)
            current_lines = []
        else:
            # Strip wiki markup for readability
            cleaned = line
            cleaned = re.sub(r"\[\[([^|\]]*\|)?([^\]]*)\]\]", r"\2", cleaned)
            cleaned = re.sub(r"'''?", "", cleaned)
            cleaned = re.sub(r"\{\{[^}]*\}\}", "", cleaned)
            current_lines.append(cleaned)

    if current_lines:
        text = "\n".join(current_lines).strip()
        if text:
            sections[current] = text

    return sections


def parse_card(page_title: str, wikitext: str) -> dict:
    """Parse a card page's wikitext into structured data."""
    infobox = parse_infobox(wikitext)
    sections = extract_sections(wikitext)

    card: dict = {
        "name_en": page_title,
        "wiki_page": page_title,
    }

    # Map infobox fields
    field_map = {
        "type": "type",
        "icon": "icon",
        "cost": "cost",
        "activation": "activation",
        "effect": "effect",
        "description": "effect",  # some cards use "description" instead of "effect"
        "quantity": "quantity",
        "expansion": "expansion",
        "image": "image",
    }
    for wiki_key, our_key in field_map.items():
        if wiki_key in infobox and our_key not in card:
            card[our_key] = infobox[wiki_key]

    # Parse activation into numbers
    if "activation" in card:
        act_str = card["activation"]
        if act_str.upper() == "N/A" or not act_str:
            card["activation_numbers"] = []
        else:
            nums: list[int] = []
            for part in re.split(r"[,\s]+", act_str):
                range_match = re.match(r"(\d+)\s*[-–]\s*(\d+)", part)
                if range_match:
                    nums.extend(range(int(range_match.group(1)), int(range_match.group(2)) + 1))
                elif part.isdigit():
                    nums.append(int(part))
            card["activation_numbers"] = sorted(set(nums))

    # Parse cost
    if "cost" in card:
        cost_match = re.match(r"(\d+)", str(card["cost"]))
        if cost_match:
            card["cost_num"] = int(cost_match.group(1))

    # Determine color from type
    type_to_color = {
        "primary industry": "blue",
        "secondary industry": "green",
        "restaurants": "red",
        "restaurant": "red",
        "major establishment": "purple",
        "landmark": "yellow",
        "landmarks": "yellow",
    }
    if "type" in card:
        card["color"] = type_to_color.get(card["type"].lower().strip(), "unknown")

    # Categories from wikitext
    categories = re.findall(r"\[\[Category:([^\]]+)\]\]", wikitext)
    card["categories"] = categories

    # Determine expansion from categories or infobox
    if "expansion" not in card:
        for cat in categories:
            if "Base Game" in cat:
                card["expansion"] = "Base Game"
                break
            elif "Harbor" in cat:
                card["expansion"] = "Harbor Expansion"
                break
            elif "Millionaire" in cat:
                card["expansion"] = "Millionaire's Row"
                break

    # Include relevant sections
    if "Gameplay" in sections:
        card["gameplay_text"] = sections["Gameplay"][:500]
    if "Strategy" in sections:
        card["strategy_text"] = sections["Strategy"][:500]

    # Store all infobox fields for reference
    card["raw_infobox"] = infobox

    return card


# ─── Main ────────────────────────────────────────────────────────────────────

def main():
    print("=== Machi Koro Card Scraper (MediaWiki API) ===\n")

    # Collect card pages from categories
    all_pages: set[str] = set()

    categories = [
        "Primary Industry",
        "Secondary Industry",
        "Restaurants",
        "Major Establishment",
        "Major Establishments",
        "Landmarks",
        "Base Game",
        "Harbor Expansion",
        "Millionaire's Row",
        "Starting Establishments",
    ]

    print("Fetching category members...")
    for cat in categories:
        members = fetch_category_members(cat)
        print(f"  Category:{cat} → {len(members)} pages")
        all_pages.update(members)
        time.sleep(0.3)

    # Also add known base game cards explicitly
    known = [
        "Wheat Field", "Ranch", "Forest", "Mine", "Apple Orchard",
        "Bakery", "Convenience Store", "Cheese Factory", "Furniture Factory",
        "Fruit and Vegetable Market", "Café", "Family Restaurant",
        "Stadium", "TV Station", "Business Center",
        "Train Station", "Shopping Mall", "Amusement Park", "Radio Tower",
    ]
    all_pages.update(known)

    # Filter out non-card pages
    skip = {"Machi Koro", "List of cards", "List of International Versions",
            "Establishment", "Landmark", "Activation number"}
    card_pages = sorted(all_pages - skip)

    print(f"\nTotal unique card pages: {len(card_pages)}")
    print(f"Pages: {card_pages[:10]}...\n")

    # Fetch and parse each card
    all_cards: list[dict] = []
    for i, title in enumerate(card_pages):
        print(f"[{i+1}/{len(card_pages)}] {title}")
        wikitext = fetch_wikitext(title)
        if not wikitext:
            print("  SKIP (empty)")
            continue

        card = parse_card(title, wikitext)
        all_cards.append(card)

        effect = card.get("effect", "(no effect)")
        cost = card.get("cost", "?")
        act = card.get("activation", "?")
        color = card.get("color", "?")
        print(f"  {color:8s} | cost={cost:>3s} | act={act:10s} | {effect[:60]}")

        time.sleep(0.3)

    # Also fetch the List_of_cards page for the master table
    print("\nFetching List_of_cards table...")
    list_wikitext = fetch_wikitext("List of cards")

    # Parse the wikitable
    table_cards: list[dict] = []
    if list_wikitext:
        # Find wikitable rows: each row starts with |- and cells with |
        in_table = False
        current_row: list[str] = []
        headers: list[str] = []

        for line in list_wikitext.split("\n"):
            if line.strip().startswith("{|"):
                in_table = True
                continue
            if line.strip().startswith("|}"):
                if current_row and headers:
                    row_dict = {}
                    for j, h in enumerate(headers):
                        if j < len(current_row):
                            row_dict[h] = current_row[j]
                    table_cards.append(row_dict)
                in_table = False
                continue
            if not in_table:
                continue

            if line.strip().startswith("|-"):
                if current_row and headers:
                    row_dict = {}
                    for j, h in enumerate(headers):
                        if j < len(current_row):
                            row_dict[h] = current_row[j]
                    table_cards.append(row_dict)
                current_row = []
            elif line.strip().startswith("!"):
                # Header row
                cells = re.split(r"\s*!!\s*", line.strip().lstrip("!"))
                headers = [c.strip() for c in cells]
            elif line.strip().startswith("|"):
                cells = re.split(r"\s*\|\|\s*", line.strip().lstrip("|"))
                for cell in cells:
                    # Clean wiki markup
                    cleaned = re.sub(r"\[\[([^|\]]*\|)?([^\]]*)\]\]", r"\2", cell)
                    cleaned = re.sub(r"\{\{[^}]*\}\}", "", cleaned)
                    cleaned = cleaned.strip()
                    current_row.append(cleaned)

    print(f"  Table rows parsed: {len(table_cards)}")

    # Write output
    output = {
        "scraped_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "source": "https://machi-koro.fandom.com/wiki/",
        "card_count": len(all_cards),
        "cards": all_cards,
        "list_table": table_cards,
    }

    outpath = "scraped_cards.json"
    with open(outpath, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f"\n=== Done! {len(all_cards)} cards scraped → {outpath} ===")

    # Summary: base game cards
    print("\n--- Base Game Cards ---")
    base_cards = [c for c in all_cards if "Base Game" in c.get("categories", []) or c.get("expansion") == "Base Game"]
    for c in sorted(base_cards, key=lambda x: x.get("activation_numbers", [0])):
        name = c["name_en"]
        color = c.get("color", "?")
        cost = c.get("cost", "?")
        act = c.get("activation", "?")
        effect = c.get("effect", "?")
        print(f"  {color:8s} {name:30s} cost={cost:>3s}  act={act:10s}  {effect}")


if __name__ == "__main__":
    main()
