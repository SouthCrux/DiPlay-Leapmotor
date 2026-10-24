import unittest

from probe_local_battery_indicator import inspect_scene


def node(name, width, height, *, x=0, y=0, hidden=False, children=None, texture=None, mask=False):
    return dict(name=name, bounds=dict(origin=dict(x=0, y=0), size=dict(width=width, height=height)),
                position=dict(x=x, y=y), anchorPoint=dict(x=0, y=0),
                transform=dict(a=1, b=0, c=0, d=1, tx=0, ty=0),
                hidden=hidden, opacity=1, children=children or [], texture=texture, maskToBounds=mask)


def fixture(glyphs=3, hidden=False, last_x=None, last_y=10):
    portals = []
    for i in range(glyphs):
        image = node('atlas', 512, 512, texture=dict(path='r/Center_Display/Fonts/default/default.png'))
        portals.append(node('glyph portal', 10, 20, x=last_x if last_x is not None and i == glyphs-1 else 10+20*i,
                            y=last_y, children=[image], mask=True))
    overlay = node('LocalBatteryOverlay', 280, 80, x=20, y=20, hidden=hidden, children=portals, mask=True)
    base = node('SimulatedMainBackground', 320, 120)
    return dict(scene=node('LocalCompositionWrapper', 320, 120, children=[base, overlay]))


class LocalBatteryIndicatorBoundsTest(unittest.TestCase):
    def test_complete_glyph_portals_fit_and_zero_remains_available(self):
        inspect_scene(fixture(), True, 3)
        inspect_scene(fixture(glyphs=2), True, 2)
        inspect_scene(fixture(glyphs=4), True, 4)

    def test_clipped_last_glyph_is_rejected_despite_correct_visible_glyph_count(self):
        with self.assertRaises(AssertionError):
            inspect_scene(fixture(last_x=275), True, 3)

    def test_vertical_clipping_and_missing_margin_are_rejected(self):
        for y in (0, 72):
            with self.subTest(y=y), self.assertRaises(AssertionError):
                inspect_scene(fixture(last_y=y), True, 3)

    def test_source_loss_hides_all_glyphs_including_cached_geometry(self):
        inspect_scene(fixture(hidden=True), False, 0)
        with self.assertRaises(AssertionError):
            inspect_scene(fixture(hidden=False), False, 0)


if __name__ == '__main__':
    unittest.main()
