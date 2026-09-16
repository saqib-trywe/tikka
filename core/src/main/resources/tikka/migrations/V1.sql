-- Schema v1. Readable in sqlite3 without tikka: statuses, resolutions and labels are words, timestamps are
-- ISO-8601 UTC text, and an issue id rebuilds from project_key and number. Derived state (blocked, ready, in
-- progress, updated) is never stored; mentions are the exception, rewritten with the text that produced them.

CREATE TABLE project (
  key         TEXT    PRIMARY KEY CHECK (length(key) BETWEEN 2 AND 10 AND key GLOB '[A-Z][A-Z0-9]*'),
  name        TEXT    NOT NULL,
  created     TEXT    NOT NULL,
  next_number INTEGER NOT NULL CHECK (next_number >= 1)
) STRICT;

CREATE TABLE issue (
  id          INTEGER PRIMARY KEY,
  project_key TEXT    NOT NULL REFERENCES project(key),
  number      INTEGER NOT NULL CHECK (number >= 1),
  title       TEXT    NOT NULL CHECK (length(trim(title)) > 0),
  body        TEXT    NOT NULL,
  status      TEXT    NOT NULL CHECK (status IN ('open', 'closed')),
  resolution  TEXT             CHECK (resolution IN ('done', 'dropped')),
  closed_at   TEXT,
  assignee    TEXT,
  claimed_at  TEXT,
  rank        REAL    NOT NULL,
  version     INTEGER NOT NULL CHECK (version >= 1),
  parent_id   INTEGER          REFERENCES issue(id),
  created     TEXT    NOT NULL,
  UNIQUE (project_key, number),
  CHECK ((status = 'closed') = (resolution IS NOT NULL)),
  CHECK ((resolution IS NULL) = (closed_at IS NULL)),
  CHECK ((assignee IS NULL) = (claimed_at IS NULL)),
  CHECK (parent_id IS NULL OR parent_id <> id)
) STRICT;

CREATE INDEX issue_by_project_status ON issue (project_key, status);
CREATE INDEX issue_by_parent         ON issue (parent_id);
CREATE INDEX issue_by_rank           ON issue (rank, number);
CREATE INDEX issue_by_assignee       ON issue (assignee);

CREATE TABLE issue_label (
  issue_id INTEGER NOT NULL REFERENCES issue(id),
  label    TEXT    NOT NULL CHECK (label = lower(label) AND length(label) > 0),
  PRIMARY KEY (issue_id, label)
) STRICT;

CREATE INDEX issue_label_by_label ON issue_label (label);

CREATE TABLE block_edge (
  blocker_id INTEGER NOT NULL REFERENCES issue(id),
  blocked_id INTEGER NOT NULL REFERENCES issue(id),
  PRIMARY KEY (blocker_id, blocked_id),
  CHECK (blocker_id <> blocked_id)
) STRICT;

CREATE INDEX block_edge_by_blocked ON block_edge (blocked_id);

-- A mention names a project key and number rather than an issue row, because a forward reference may name an issue
-- that does not exist yet. Prose mentions (title and body) are rewritten on every edit; comment mentions are
-- permanent, because comments are never edited.
CREATE TABLE mention (
  source_id     INTEGER NOT NULL REFERENCES issue(id),
  source_kind   TEXT    NOT NULL CHECK (source_kind IN ('prose', 'comment')),
  target_key    TEXT    NOT NULL,
  target_number INTEGER NOT NULL,
  PRIMARY KEY (source_id, source_kind, target_key, target_number)
) STRICT;

CREATE INDEX mention_by_target ON mention (target_key, target_number);

-- One write is one event. AUTOINCREMENT makes the sequence global and monotonic, which is what orders timelines:
-- timestamps can repeat or step backwards.
CREATE TABLE event (
  seq      INTEGER PRIMARY KEY AUTOINCREMENT,
  issue_id INTEGER NOT NULL REFERENCES issue(id),
  at       TEXT    NOT NULL,
  actor    TEXT    NOT NULL,
  comment  TEXT
) STRICT;

CREATE INDEX event_by_issue ON event (issue_id, seq);

CREATE TABLE change (
  event_seq INTEGER NOT NULL REFERENCES event(seq),
  ordinal   INTEGER NOT NULL,
  field     TEXT    NOT NULL,
  op        TEXT    NOT NULL CHECK (op IN ('set', 'add', 'remove')),
  old       TEXT,
  new       TEXT,
  PRIMARY KEY (event_seq, ordinal)
) STRICT;

-- The other timelines an event appears on: edge counterparts, and issues it unblocked or blocked again.
CREATE TABLE event_related (
  event_seq INTEGER NOT NULL REFERENCES event(seq),
  issue_id  INTEGER NOT NULL REFERENCES issue(id),
  PRIMARY KEY (event_seq, issue_id)
) STRICT;

CREATE INDEX event_related_by_issue ON event_related (issue_id, event_seq);

-- Trigram tokenizer: `text:` matches like grep, so text:claim finds "reclaimed". rowid is the issue id, and the row
-- is rewritten in the same transaction as the title, body or comment that feeds it.
CREATE VIRTUAL TABLE issue_fts USING fts5 (title, body, comments, tokenize = 'trigram');
