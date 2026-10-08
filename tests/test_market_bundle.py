import importlib.util
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    'package_kline', Path(__file__).resolve().parents[1]/'scripts/package-kline-bundle.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class BundleTests(unittest.TestCase):
    def test_turnover_is_packaged_without_unrelated_market_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in (*package.MEMBERS, package.MARKET_MEMBER, 'cache/market/margin_daily.csv'):
                path = root/name
                path.parent.mkdir(parents=True, exist_ok=True)
                if name == 'cache/kline':
                    path.mkdir()
                else:
                    path.write_text('{}', encoding='utf-8')
            archive = root/'fixture.tar'
            with tarfile.open(archive, 'w') as tar:
                for name in package.bundle_members(root):
                    tar.add(root/name, arcname=name)
            with tarfile.open(archive) as tar:
                names = tar.getnames()
                self.assertIn(package.MARKET_MEMBER, names)
                self.assertNotIn('cache/market/margin_daily.csv', names)

    def test_old_bundle_without_market_data_is_compatible(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(package.bundle_members(Path(tmp)), package.MEMBERS)


if __name__ == '__main__':
    unittest.main()
