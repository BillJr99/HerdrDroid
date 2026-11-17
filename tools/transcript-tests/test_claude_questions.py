"""Synthetic Claude JSONL records: user prose is not an AskUserQuestion call."""
import json
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_kiro_reader import load


class ClaudeQuestions(unittest.TestCase):
    def read(self, records):
        with tempfile.TemporaryDirectory() as home:
            cwd = '/work/sample'
            folder = Path(home, '.claude', 'projects', re.sub(r'[^A-Za-z0-9]', '-', cwd))
            folder.mkdir(parents=True)
            (folder / 'demo.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in records))
            return load(home).claude(cwd, {})['items']

    def user(self, text):
        return {'type': 'user', 'message': {'role': 'user', 'content': text}}

    def test_user_numbered_text_is_only_a_user_item(self):
        items = self.read([self.user('1. Review first\n2. Save later')])
        self.assertEqual([i['t'] for i in items], ['user'])

    def test_options_and_results_stay_with_their_tool_ids(self):
        question = {'questions': [{'header': 'Color', 'question': 'Which color?',
                    'options': [{'label': 'Blue', 'description': 'A cool color.'},
                                {'label': 'Orange', 'description': 'A warm color.'}]}]}
        items = self.read([
            self.user('1. Review first\n2. Save later'),
            {'type': 'assistant', 'message': {'role': 'assistant', 'content': [
                {'type': 'tool_use', 'id': 'ask-a', 'name': 'AskUserQuestion', 'input': question}]}},
            self.user('1. Keep the draft\n2. Keep the sample'),
            {'type': 'user', 'message': {'role': 'user', 'content': [
                {'type': 'tool_result', 'tool_use_id': 'ask-a',
                 'content': 'User has answered your questions: "Which color?"="Blue".'}]}},
        ])
        self.assertEqual([i['t'] for i in items], ['user', 'tool', 'user', 'result'])
        self.assertEqual(json.loads(items[1]['input']), question)
        self.assertEqual(items[1]['id'], items[3]['ref'])


if __name__ == '__main__':
    unittest.main()
