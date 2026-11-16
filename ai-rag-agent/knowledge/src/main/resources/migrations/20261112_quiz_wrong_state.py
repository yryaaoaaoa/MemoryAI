"""Persist current wrong-book membership. Run with the backend stopped.

python 20261112_quiz_wrong_state.py --config /path/application.yml --backup-dir /path/backup
Requires PyMySQL, redis and PyYAML. Credentials are read locally and never printed.
"""
import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit

import pymysql
import redis
import yaml


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--backup-dir', type=Path, required=True)
    args = parser.parse_args()
    spring = yaml.safe_load(args.config.read_text(encoding='utf-8'))['spring']
    source = spring['datasource']
    url = urlsplit(source['url'].removeprefix('jdbc:'))
    db = pymysql.connect(host=url.hostname, port=url.port or 3306, user=source['username'],
                         password=str(source['password']), database=url.path.lstrip('/'), charset='utf8mb4')
    cache_config = spring['data']['redis']
    cache = redis.Redis(host=cache_config.get('host', 'localhost'), port=cache_config.get('port', 6379),
                        db=cache_config.get('database', 0), password=cache_config.get('password') or None,
                        decode_responses=True)
    migrate(db, cache, args.backup_dir, url.path.lstrip('/'))
    db.close()


def migrate(db, cache, backup_dir, database):
    # The snapshot is also a resume point if ALTER TABLE succeeded before backfill failed.
    backup_dir.mkdir(parents=True, exist_ok=True)
    snapshot_path = backup_dir / 'quiz-wrong-state-before.json'
    done_path = backup_dir / 'quiz-wrong-state-complete.json'
    with db.cursor(pymysql.cursors.DictCursor) as cursor:
        cursor.execute("SHOW COLUMNS FROM quiz_record LIKE 'wrong_removed'")
        has_column = cursor.fetchone() is not None
        if has_column and (done_path.exists() or not snapshot_path.exists()):
            print('wrong_removed already exists; existing state left unchanged.')
            return
        if snapshot_path.exists():
            snapshot = json.loads(snapshot_path.read_text(encoding='utf-8'))
            assert snapshot['database'] == database, 'Backup belongs to another database'
        else:
            cache.ping()  # Fail before changing MySQL if old membership cannot be read.
            cursor.execute('SELECT * FROM quiz_record ORDER BY id')
            records = cursor.fetchall()
            user_ids = sorted({r['user_id'] for r in records})
            active = {str(uid): sorted(cache.zrange(f'wrong:set:{uid}', 0, -1)) for uid in user_ids}
            snapshot = {'database': database, 'records': records, 'active': active}
            snapshot_path.write_text(json.dumps(snapshot, ensure_ascii=False, default=str), encoding='utf-8')
        if not has_column:
            cursor.execute("ALTER TABLE quiz_record ADD COLUMN wrong_removed TINYINT NOT NULL DEFAULT 0 COMMENT '已手动移出错题本；不改变答题结果'")
        # Preserve the currently visible old wrong-book membership, including manual removals.
        removed = [(r['id'],) for r in snapshot['records'] if not r['is_correct']
                   and str(r['question_id']) not in snapshot['active'].get(str(r['user_id']), [])]
        if removed:
            cursor.executemany('UPDATE quiz_record SET wrong_removed=1 WHERE id=%s', removed)
        db.commit()
        report = {'backedUpRecords': len(snapshot['records']), 'preservedRemovedRecords': len(removed)}
        done_path.write_text(json.dumps(report), encoding='utf-8')
        print(json.dumps(report))


if __name__ == '__main__':
    main()
