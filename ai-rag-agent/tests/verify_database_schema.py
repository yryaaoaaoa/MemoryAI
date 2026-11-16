"""Exercise fresh schema and data-preserving upgrades in an isolated MySQL 8 instance.

Requires mysql, mysqld on PATH and PyMySQL, redis, PyYAML; never reads application datasource configuration.
Run: python ai-rag-agent/tests/verify_database_schema.py
"""
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import runpy
import json
import pymysql


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "knowledge/src/main/resources"


def main():
    mysql, mysqld = shutil.which("mysql"), shutil.which("mysqld")
    if not mysql or not mysqld:
        raise SystemExit("mysql and mysqld must be available on PATH")
    with tempfile.TemporaryDirectory(prefix="memoryai-schema-test-") as temp:
        data = Path(temp) / "data"
        result = subprocess.run(
            [mysqld, "--no-defaults", "--initialize-insecure", f"--datadir={data}",
             "--innodb-buffer-pool-size=32M"], capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stderr)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        log = open(Path(temp) / "server.log", "w", encoding="utf-8")
        process = subprocess.Popen(
            [mysqld, "--no-defaults", f"--datadir={data}", f"--port={port}",
             "--bind-address=127.0.0.1", "--mysqlx=OFF", "--innodb-buffer-pool-size=32M",
             "--skip-log-bin", "--console"], stdout=log, stderr=log)
        base = [mysql, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                f"--port={port}", "--user=root", "--default-character-set=utf8mb4",
                "--batch", "--skip-column-names", "--connect-timeout=1"]

        def sql(text, database=None, fail=False):
            cmd = base + ([database] if database else [])
            result = subprocess.run(cmd, input=text, capture_output=True, text=True,
                                    encoding="utf-8", timeout=60)
            if fail:
                assert result.returncode != 0, "Expected SQL rejection"
                assert "Duplicate entry" in result.stderr, result.stderr
            elif result.returncode:
                raise AssertionError(result.stderr)
            return result.stdout.strip()

        try:
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError("Temporary MySQL stopped before becoming ready")
                try:
                    probe = subprocess.run(base, input="SELECT 1;", text=True,
                                           capture_output=True, timeout=5)
                except subprocess.TimeoutExpired:
                    continue
                if probe.returncode == 0:
                    break
                time.sleep(.2)
            else:
                raise RuntimeError("Temporary MySQL did not become ready")
            sql("CREATE DATABASE fresh CHARACTER SET utf8mb4; CREATE DATABASE legacy CHARACTER SET utf8mb4;")
            schema = (RESOURCES / "schema.sql").read_text(encoding="utf-8")
            migration = (RESOURCES / "migrations/20261022_schema_repair.sql").read_text(encoding="utf-8")
            sql(schema, "fresh")
            sql(schema, "fresh")
            sql((ROOT / "tests/fixtures/legacy_schema.sql").read_text(encoding="utf-8"), "legacy")
            sql("""
                INSERT INTO `user`(id,username,password_hash) VALUES (11,'existing','preserve-hash');
                INSERT INTO knowledge_base(id,name) VALUES (1,'public-after-owner-confirmed'),(2,'unknown-owner');
                INSERT INTO document(id,kb_id,file_name,file_hash) VALUES (1,1,'existing.txt','hash-a'),(2,2,'private.txt','hash-b');
                INSERT INTO resume(id,user_id,file_name,file_hash,raw_text) VALUES (1,11,'resume.pdf','resume-a','原始简历');
                INSERT INTO interview_session(session_id,resume_id,questions_json) VALUES ('owned',1,'[]'),('unknown',NULL,'[]');
                INSERT INTO interview_answer(session_id,question_index,question,user_answer,score) VALUES ('owned',0,'question','answer',81);
                ALTER TABLE document RENAME INDEX uk_file_hash TO renamed_global_hash;
                ALTER TABLE resume RENAME INDEX uk_file_hash TO renamed_global_hash;
            """, "legacy")
            sql(migration, "legacy")
            audit_migration = (RESOURCES / "migrations/20261112_full_audit_repair.sql").read_text(encoding="utf-8")
            sql(audit_migration, "legacy")
            sql(audit_migration, "legacy")
            notes_migration = (RESOURCES / "migrations/20261113_learning_notes.sql").read_text(encoding="utf-8")
            sql(notes_migration, "legacy")
            sql(notes_migration, "legacy")
            integrity_migration = (RESOURCES / "migrations/20261113_learning_notes_integrity.sql").read_text(encoding="utf-8")
            sql(integrity_migration, "legacy")
            sql(integrity_migration, "legacy")
            sql("""INSERT INTO learning_note(user_id,title,confusion,content,status,source_session_id,source_message_ids,source_snapshot)
                   VALUES (11,'existing note','','handwritten footer','PENDING',1,'[]','[]');""", "legacy")
            ranges_migration = (RESOURCES / "migrations/20261113_learning_note_source_ranges.sql").read_text(encoding="utf-8")
            sql(ranges_migration, "legacy")
            sql("UPDATE learning_note SET source_ranges='[]' WHERE user_id=11;", "legacy")
            sql(ranges_migration, "legacy")
            assert sql("SELECT content,source_ranges,version FROM learning_note WHERE user_id=11;", "legacy") == 'handwritten footer\t[]\t0'
            tree_migration = (RESOURCES / "migrations/20261116_learning_note_tree.sql").read_text(encoding="utf-8")
            sql(tree_migration, "legacy")
            sql(tree_migration, "legacy")
            assert sql("SELECT content,tree_json IS NULL,version FROM learning_note WHERE user_id=11;", "legacy") == 'handwritten footer\t1\t0'
            tree = '{"version":1,"nodes":[{"id":"root","title":"知识点","body":"正文","children":[]}]}'
            sql(f"UPDATE learning_note SET tree_json='{tree}' WHERE user_id=11;", "legacy")
            sql(tree_migration, "legacy")
            assert sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(tree_json,'$.nodes[0].body')) FROM learning_note WHERE user_id=11;", "legacy") == '正文'
            # The newer migration preserves manual removals from the old Redis-only book.
            sql("""INSERT INTO quiz_record(user_id,question_id,user_answer,is_correct)
                   VALUES (11,501,'B',0),(11,502,'B',0);""", "legacy")
            migrate_wrong = runpy.run_path(str(RESOURCES / "migrations/20261112_quiz_wrong_state.py"))["migrate"]
            class OldWrongBook:
                def ping(self): return True
                def zrange(self, key, start, end):
                    assert key == 'wrong:set:11'
                    return ['501']
            with pymysql.connect(host='127.0.0.1',port=port,user='root',database='legacy') as connection:
                migrate_wrong(connection, OldWrongBook(), Path(temp) / 'wrong-backup', 'legacy')
                migrate_wrong(connection, OldWrongBook(), Path(temp) / 'wrong-backup', 'legacy')
            assert sql('SELECT question_id,wrong_removed FROM quiz_record ORDER BY id;', 'legacy') == '501\t0\n502\t1'
            # Simulate an existing installation whose public KB ownership is known.
            sql("UPDATE knowledge_base SET user_id=0 WHERE id=1; UPDATE document SET user_id=11 WHERE id=1;", "legacy")
            sql(migration, "legacy")
            assert sql("SELECT user_id FROM document WHERE id=1;", "legacy") == "0"
            assert sql("SELECT user_id IS NULL FROM document WHERE id=2;", "legacy") == "1"
            assert sql("SELECT user_id FROM interview_session WHERE session_id='owned';", "legacy") == "11"
            assert sql("SELECT user_id IS NULL FROM interview_session WHERE session_id='unknown';", "legacy") == "1"
            assert sql("SELECT user_answer,score FROM interview_answer;", "legacy") == "answer\t81"
            assert sql("SELECT raw_text FROM resume WHERE id=1;", "legacy") == "原始简历"
            assert sql("SELECT password_hash,role,is_deleted FROM `user`;", "legacy") == "preserve-hash\tuser\t0"
            # Full model mapping, not a hand-picked subset of repaired columns.
            entities = 0
            for path in ROOT.rglob("*.java"):
                if "target" in path.parts:
                    continue
                text = path.read_text(encoding="utf-8")
                match = re.search(r'@TableName\("([^"]+)"\)', text)
                if not match:
                    continue
                entities += 1
                fields = re.findall(r"private\s+[\w<>]+\s+(\w+)\s*;", text)
                columns = [re.sub(r"([A-Z])", r"_\1", f).lower() for f in fields]
                for db in ("fresh", "legacy"):
                    sql("SELECT " + ",".join(f"`{c}`" for c in columns)
                        + f" FROM `{match[1]}` LIMIT 0;", db)
            assert entities >= 10, entities
            signature = """SELECT table_name,column_name,column_type,is_nullable,COALESCE(column_default,'<NULL>'),extra
                FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,column_name;"""
            assert sql(signature, "fresh") == sql(signature, "legacy"), "Fresh/upgraded columns differ"
            indexes = """SELECT table_name,index_name,non_unique,seq_in_index,column_name
                FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index;"""
            assert sql(indexes, "fresh") == sql(indexes, "legacy"), "Fresh/upgraded indexes differ"
            for db in ("fresh", "legacy"):
                sql("""
                    INSERT INTO document(kb_id,user_id,file_name,file_hash) VALUES (100,11,'a','same'),(101,11,'b','same');
                    INSERT INTO resume(user_id,file_name,file_hash) VALUES (100,'a','same'),(101,'b','same');
                    INSERT INTO user_skill_profile(user_id,skill_id,profile_json,interview_count) VALUES (11,'java','{}',1)
                        ON DUPLICATE KEY UPDATE profile_json=VALUES(profile_json),interview_count=interview_count+1,updated_at=NOW();
                    INSERT INTO user_skill_profile(user_id,skill_id,profile_json,interview_count) VALUES (11,'java','{}',1)
                        ON DUPLICATE KEY UPDATE profile_json=VALUES(profile_json),interview_count=interview_count+1,updated_at=NOW();
                """, db)
                sql("INSERT INTO document(kb_id,file_name,file_hash) VALUES (100,'duplicate','same');", db, fail=True)
                sql("INSERT INTO resume(user_id,file_name,file_hash) VALUES (100,'duplicate','same');", db, fail=True)
                assert sql("SELECT interview_count FROM user_skill_profile WHERE user_id=11;", db) == "2"
                # Repeat migration after valid cross-owner duplicates exist: global hash uniqueness must not return.
                sql(migration, db)
            # Run the actual service SQL in MySQL, including the original overflow boundary.
            source = (ROOT / 'agent/src/main/java/com/jobai/agent/service/WrongQuestionService.java').read_text(encoding='utf-8')
            quiz_upsert = source.split('private void updateUserMastery', 1)[1].split('jdbc.update("""', 1)[1].split('"""', 1)[0]
            interview_upsert = source.split('public void updateMasteryFromInterview', 1)[1].split('jdbc.update("""', 1)[1].split('"""', 1)[0]
            with pymysql.connect(host='127.0.0.1',port=port,user='root',database='fresh',autocommit=True) as connection:
                with connection.cursor() as cursor:
                    # Exercise the production mapper SQL against MySQL, including NULL tree clearing.
                    mapper = (ROOT / 'rag/src/main/java/com/jobai/rag/mapper/LearningNoteMapper.java').read_text(encoding='utf-8')
                    insert_note = mapper.split('@Insert("""',1)[1].split('"""',1)[0]
                    update_note = mapper.split('@Update("""',1)[1].split('"""',1)[0]
                    payload = dict(userId=42,title='tree note',confusion='',content='## Root\n\nBody\n',treeJson=tree,
                                   status='REVIEW',version=0,createRequestId='10000000-0000-0000-0000-000000000042',
                                   createRequestHash='a'*64,sourceSessionId=1,sourceMessageIds='[]',sourceSnapshot='[]',
                                   createdAt='2026-11-16 00:00:00',updatedAt='2026-11-16 00:00:00')
                    insert_sql = re.sub(r'#\{(\w+)\}',r'%(\1)s',insert_note)
                    cursor.execute(insert_sql,payload)
                    cursor.execute('SELECT id,tree_json,content,version FROM learning_note WHERE user_id=42')
                    note_id, stored_tree, content, version = cursor.fetchone()
                    assert json.loads(stored_tree) == json.loads(tree)
                    assert content == payload['content'] and version == 0
                    update_sql = re.sub(r'#\{(?:note\.)?(\w+)\}',r'%(\1)s',update_note)
                    payload.update(id=note_id,expectedVersion=0,content='edited plain text',treeJson=None)
                    assert cursor.execute(update_sql,payload) == 1
                    assert cursor.execute(update_sql,payload) == 0
                    cursor.execute('SELECT tree_json,content,version FROM learning_note WHERE id=%s',(note_id,))
                    assert cursor.fetchone() == (None,'edited plain text',1)
                    for _ in range(30):
                        cursor.execute(quiz_upsert.replace('?', '%s'), (42,'sql-test',100,1,1,1))
                    cursor.execute('SELECT mastery,total_attempts,correct_attempts,streak,DATEDIFF(next_review_at,CURDATE()) FROM user_mastery WHERE user_id=42')
                    assert cursor.fetchone() == (100,30,30,30,30)
                    cursor.execute(quiz_upsert.replace('?', '%s'), (42,'sql-test',0,0,0,0))
                    cursor.execute('SELECT mastery,total_attempts,correct_attempts,streak,DATEDIFF(next_review_at,CURDATE()) FROM user_mastery WHERE user_id=42')
                    assert cursor.fetchone() == (97,31,30,0,1)
                    cursor.execute(interview_upsert.replace('?', '%s'), (43,'interview-test',0,4,0))
                    cursor.execute(interview_upsert.replace('?', '%s'), (43,'interview-test',100,2,2))
                    cursor.execute('SELECT mastery,total_attempts,correct_attempts FROM user_mastery WHERE user_id=43')
                    assert cursor.fetchone() == (33,6,2)
            sql("UPDATE interview_session SET user_id=12 WHERE session_id='owned'; UPDATE `user` SET role='admin' WHERE id=11;", "legacy")
            sql(migration, "legacy")
            assert sql("SELECT user_id FROM interview_session WHERE session_id='owned';", "legacy") == "12"
            assert sql("SELECT role FROM `user` WHERE id=11;", "legacy") == "admin"
            print(f"PASS: MySQL fresh schema, {entities} entity mappings, legacy upgrades, repeatability, data preservation, scoped uniqueness, wrong-book migration, quiz/interview mastery SQL")
        finally:
            # Windows mysqld may supervise a child process. SQL shutdown closes both;
            # terminating only the parent would leave the datadir locked.
            subprocess.run(base, input="SHUTDOWN;", text=True, capture_output=True, timeout=10)
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            log.close()


if __name__ == "__main__":
    main()
