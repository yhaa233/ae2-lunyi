import re

PATH = r"E:\creat-id\src\main\java\com\dianzhong\creatid\RecipeScanner.java"
with open(PATH, encoding="utf-8") as fh:
    text = fh.read()

marker = 'if (recipe.getClass().getSimpleName().contains("SequencedAssembly")) {'
start = text.find(marker)
if start < 0:
    print("marker not found")
    raise SystemExit(1)

# 找到这个 if 块的结尾（大括号配对）
i = text.find("{", start)
depth = 0
end = -1
for j in range(i, len(text)):
    ch = text[j]
    if ch == "{":
        depth += 1
    elif ch == "}":
        depth -= 1
        if depth == 0:
            end = j
            break
if end < 0:
    print("block end not found")
    raise SystemExit(1)

cleanup = '''
            // 过渡物品只是过程中的中间产物：从槽位里按 id 剔除
            for (int si = 0; si < slots.size(); si++) {
                String slot = slots.get(si);
                int at = slot.lastIndexOf('#');
                String idsPart = at > 0 ? slot.substring(0, at) : slot;
                String amountPart = at > 0 ? slot.substring(at) : "#1";
                List<String> keep = new ArrayList<>();
                int total = 0;
                for (String id : idsPart.split("\\\\|")) {
                    total++;
                    String trimmed = id.trim();
                    if (!transitional.contains(trimmed)) {
                        keep.add(trimmed);
                    }
                }
                if (keep.isEmpty()) {
                    slots.remove(si);
                    si--;
                } else if (keep.size() != total) {
                    slots.set(si, String.join("|", keep) + amountPart);
                }
            }
'''

text = text[:end] + cleanup + text[end:]
with open(PATH, "w", encoding="utf-8") as fh:
    fh.write(text)
print("cleanup inserted at", end)
