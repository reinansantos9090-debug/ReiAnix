'''Flet-managed dialog lifecycle helpers.'''
from __future__ import annotations


def dismiss_dialog(page, dialog=None) -> None:
    '''Dismiss the top-most dialog through Flet's managed dialog stack.

    ``dialog`` is retained for call-site compatibility. Dialog creation and
    teardown stay inside ``page.show_dialog()``/``page.pop_dialog()`` so views
    never mutate ``page.overlay`` directly.
    '''
    page.pop_dialog()
