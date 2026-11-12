import json
import os
import re
from collections import Counter

TSV = r"F:\mc\versions\NAST hard d1.2.1\logs\recipes-startup-20261112-142054.tsv"
OUT = r"E:\lunyi\src\main\resources\data\ae2_lunyi\recipe"
os.makedirs(OUT, exist_ok=True)

made = []
skipped = []

with open(TSV, encoding="utf-8") as fh:
    for line in fh:
        cols = line.rstrip("\n").split("\t")
        if len(cols) < 5:
            continue
        rid, rtype, inputs, outputs, slots = cols[0], cols[1], cols[2], cols[3], cols[4]
        if rtype != "minecraft:crafting_shaped":
            continue
        if not outputs:
            continue
        m = re.match(r"(.+?)x(\d+)$", outputs)
        if not m:
            continue
        out_id, out_count = m.group(1), int(m.group(2))
        if not out_id.endswith("_smithing_template"):
            continue
        items = [x for x in inputs.split(",") if x]
        if not items:
            continue
        counter = Counter(items)
        template = out_id
        if counter.get(template, 0) < 1:
            skipped.append((rid, "输出模板不在输入里"))
            continue
        # 去掉模板那一个，剩下数量最多的就是材料
        rest = Counter(items)
        rest[template] -= 1
        if rest[template] <= 0:
            del rest[template]
        if not rest:
            skipped.append((rid, "找不到材料"))
            continue
        material, count = rest.most_common(1)[0]
        if count != 7:
            skipped.append((rid, f"材料数量是 {count} 而不是 7"))
            continue
        # 中间那个不是模板、也不是主材料的物品，原样保留
        center = None
        for item, n in rest.items():
            if item != material and n >= 1:
                center = item
                break
        if center is None:
            skipped.append((rid, "找不到中间物品"))
            continue
        name = out_id.split(":")[1]
        path = os.path.join(OUT, f"template_dup_{name}.json")
        data = {
            "type": "minecraft:crafting_shaped",
            "category": "misc",
            "pattern": ["MMM", "MCM", "MMM"],
            "key": {"M": {"item": material}, "C": {"item": center}},
            "result": {"id": template, "count": 1},
        }
        with open(path, "w", encoding="utf-8") as out:
            json.dump(data, out, ensure_ascii=False, indent=2)
            out.write("\n")
        made.append((name, material, out_count))

print(f"生成 {len(made)} 条：")
for name, material, cnt in made:
    print(f"  {name}: 8 x {material} + 模板 -> {cnt}")
if skipped:
    print(f"跳过 {len(skipped)} 条：")
    for rid, why in skipped[:10]:
        print(f"  {rid}: {why}")
