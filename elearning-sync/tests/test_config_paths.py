"""Configuration path resolution regression tests.

The GUI and CLI are often started from a shortcut, a scheduler, or another
working directory. Relative paths in a config must therefore stay relative
to the config file, not to whichever directory happened to launch the app.
"""

from __future__ import annotations

import os
import tempfile
import unittest

from fudan_sync.config import load_config


class ConfigPathTests(unittest.TestCase):
    def test_relative_paths_are_anchored_to_config_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            nested = os.path.join(tmp, "profile")
            os.makedirs(nested)
            config_path = os.path.join(nested, "config.yaml")
            with open(config_path, "w", encoding="utf-8") as handle:
                handle.write(
                    """base_url: https://example.invalid
root_dir: ./files
state_db: ./data/state.db
log_file: logs/app.log
auth:
  method: cookie
  cookie_file: ./secrets/cookies.json
"""
                )

            cfg = load_config(config_path)

            self.assertEqual(cfg.config_path, os.path.abspath(config_path))
            self.assertEqual(cfg.root_dir, os.path.join(nested, "files"))
            self.assertEqual(cfg.state_db, os.path.join(nested, "data", "state.db"))
            self.assertEqual(cfg.log_file, os.path.join(nested, "logs", "app.log"))
            self.assertEqual(
                cfg.cookie_file,
                os.path.join(nested, "secrets", "cookies.json"),
            )

    def test_absolute_paths_are_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            config_path = os.path.join(tmp, "config.yaml")
            root = os.path.join(tmp, "absolute-files")
            state = os.path.join(tmp, "absolute-state.db")
            cookie = os.path.join(tmp, "absolute-cookies.json")
            with open(config_path, "w", encoding="utf-8") as handle:
                handle.write(
                    f"""root_dir: {root}
state_db: {state}
log_file:
auth:
  method: cookie
  cookie_file: {cookie}
"""
                )

            cfg = load_config(config_path)

            self.assertEqual(cfg.root_dir, os.path.normpath(root))
            self.assertEqual(cfg.state_db, os.path.normpath(state))
            self.assertEqual(cfg.cookie_file, os.path.normpath(cookie))

    def test_missing_config_uses_anchored_absolute_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            nested = os.path.join(tmp, "new-profile")
            config_path = os.path.join(nested, "config.yaml")

            cfg = load_config(config_path)

            self.assertEqual(cfg.config_path, os.path.abspath(config_path))
            self.assertEqual(cfg.root_dir, os.path.join(nested, "elearning_files"))
            self.assertEqual(cfg.state_db, os.path.join(nested, "sync_state.db"))
            self.assertEqual(cfg.cookie_file, os.path.join(nested, "cookies.json"))
            self.assertEqual(cfg.log_file, os.path.join(nested, "sync.log"))

    def test_relative_config_path_is_normalized_to_absolute(self):
        with tempfile.TemporaryDirectory() as tmp:
            previous = os.getcwd()
            try:
                os.chdir(tmp)
                cfg = load_config(os.path.join("profile", "config.yaml"))
            finally:
                os.chdir(previous)

            expected_dir = os.path.join(tmp, "profile")
            self.assertEqual(cfg.config_path, os.path.join(expected_dir, "config.yaml"))
            self.assertEqual(cfg.root_dir, os.path.join(expected_dir, "elearning_files"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
