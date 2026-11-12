import re

PATH = r"E:\creat-id\src\main\java\com\dianzhong\creatid\RecipeScanner.java"
with open(PATH, encoding="utf-8") as fh:
    text = fh.read()

start = text.find("            java.util.Set<String> transitional = new java.util.HashSet<>();")
if start < 0:
    print("start not found")
    raise SystemExit(1)

anchor = "            for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {"
end = text.find(anchor, start)
if end < 0:
    print("end not found")
    raise SystemExit(1)

block = '''            java.util.Set<String> transitional = new java.util.HashSet<>();
            for (java.lang.reflect.Field field : hierarchyFields(recipe.getClass())) {
                String fname = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (!fname.contains("transition")) {
                    continue;
                }
                try {
                    Object value = field.get(recipe);
                    if (value instanceof Iterable<?> list) {
                        for (Object element : list) {
                            String elementId = itemIdOf(element);
                            if (elementId != null) {
                                transitional.add(elementId);
                            }
                        }
                    } else {
                        String valueId = itemIdOf(value);
                        if (valueId != null) {
                            transitional.add(valueId);
                        }
                    }
                } catch (Throwable ignored) {
                    // 跳过这个字段
                }
            }
'''

text = text[:start] + block + text[end:]
with open(PATH, "w", encoding="utf-8") as fh:
    fh.write(text)
print("rewritten")
