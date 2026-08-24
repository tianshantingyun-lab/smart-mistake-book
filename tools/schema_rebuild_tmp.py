# -*- coding: utf-8 -*-
"""TEMPORARY (task #39, cluster A): reconstruct the exported Room 34.json
from the runtime dump produced by SchemaDumpInstrumentedTest
(room34-dump.txt in the repo root, fetched via adb pull). Delete together
with .github/workflows/schema-export.yml once schema alignment is restored.
"""
import io
import json
import sys

DUMP = "room34-dump.txt"
SCHEMA = "core/database/schemas/com.tingyun.smartmistakebook.core.database.StudyDatabase/34.json"


def main():
    lines = io.open(DUMP, encoding="utf-8").read().split("\n")
    assert lines and lines[0].startswith("IDENTITY_HASH "), "missing identity hash line"
    hash_ = lines[0].split(" ", 1)[1].strip()

    tables, views, indices, triggers = {}, {}, {}, {}
    for ln in lines[1:]:
        parts = ln.split("\u001f")
        if len(parts) != 4:
            continue
        typ, name, tbl_name, sql = parts
        if not sql:
            continue
        if typ == "table":
            tables[name] = sql
        elif typ == "view":
            views[name] = sql
        elif typ == "index":
            indices[name] = sql
        elif typ == "trigger":
            triggers[name] = sql
    print("dump objects: tables=%d views=%d indices=%d triggers=%d"
          % (len(tables), len(views), len(indices), len(triggers)))

    with io.open(SCHEMA, encoding="utf-8") as f:
        schema = json.load(f)
    db = schema["database"]
    print("identityHash: %s -> %s" % (db.get("identityHash"), hash_))
    db["identityHash"] = hash_

    missing = []
    for entity in db.get("entities", []):
        tname = entity["tableName"]
        if tname in tables:
            entity["createSql"] = tables[tname].replace("`%s`" % tname, "`${TABLE_NAME}`", 1)
        else:
            missing.append("table:" + tname)
        for index in entity.get("indices", []):
            iname = index["name"]
            if iname in indices:
                index["createSql"] = indices[iname].replace("`%s`" % tname, "`${TABLE_NAME}`", 1)
            else:
                missing.append("index:" + iname)
        for i, trig_sql in enumerate(entity.get("contentSyncTriggers", []) or []):
            toks = trig_sql.split()
            trig_name = toks[5] if len(toks) > 5 and toks[1] == "TRIGGER" else None
            if trig_name and trig_name in triggers:
                entity["contentSyncTriggers"][i] = triggers[trig_name]
            else:
                missing.append("trigger:" + str(trig_name))
    for view in db.get("views", []):
        vname = view["viewName"]
        if vname in views:
            view["createSql"] = views[vname].replace("`%s`" % vname, "`${VIEW_NAME}`", 1)
        else:
            missing.append("view:" + vname)

    if missing:
        print("MISSING FROM DUMP:", missing)
        sys.exit(1)

    db["setupQueries"] = [
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '%s')" % hash_,
    ]
    with io.open(SCHEMA, "w", encoding="utf-8") as f:
        json.dump(schema, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("34.json reconstructed from runtime dump")


if __name__ == "__main__":
    main()
