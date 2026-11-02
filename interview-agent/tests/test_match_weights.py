import unittest

from app.match_graph import DEFAULT_WEIGHTS, WEIGHT_BOUNDS, WEIGHT_KEYS, _normalize_weights


class MatchWeightTests(unittest.TestCase):
    def test_default_profile_is_preserved(self):
        result = _normalize_weights(DEFAULT_WEIGHTS)
        self.assertEqual(result, DEFAULT_WEIGHTS)
        self.assertEqual(sum(result.values()), 100)

    def test_missing_weight_keys_fall_back_to_default(self):
        self.assertEqual(_normalize_weights({}), DEFAULT_WEIGHTS)
        self.assertEqual(_normalize_weights({"skills": 40}), DEFAULT_WEIGHTS)

    def test_weights_are_bounded_and_normalized(self):
        result = _normalize_weights({
            "skills": 999,
            "role": -10,
            "experience": 80,
            "education": 80,
            "city": 80,
            "evidence": 80,
        })
        self.assertEqual(set(result), set(WEIGHT_KEYS))
        self.assertEqual(sum(result.values()), 100)
        for key, value in result.items():
            low, high = WEIGHT_BOUNDS[key]
            self.assertGreaterEqual(value, low)
            self.assertLessEqual(value, high)

    def test_skill_and_role_cannot_be_zeroed_out(self):
        result = _normalize_weights({
            "skills": 0,
            "role": 0,
            "experience": 30,
            "education": 20,
            "city": 15,
            "evidence": 25,
        })
        self.assertGreaterEqual(result["skills"], WEIGHT_BOUNDS["skills"][0])
        self.assertGreaterEqual(result["role"], WEIGHT_BOUNDS["role"][0])


if __name__ == "__main__":
    unittest.main()