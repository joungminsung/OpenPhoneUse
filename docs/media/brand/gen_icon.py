"""Source of the app icon: icon.svg (neon glass, dark mode) and icon-light.svg (paper glass, light mode).

Run it in this folder. It writes the two tiles, the 432 px adaptive layers as SVG (*-bg.svg, *-fg.svg) and
symbols.html for the landing page. The PNGs in this folder and in app/src/main/res/drawable-nodpi (light) and
drawable-night-nodpi (dark) are those SVGs rendered in Chrome, which draws the blur filters faithfully.
"""
K = 1.0   # scale of the mark around the centre
SW = 58   # outline weight, heavy enough to read at launcher size; the glow layers follow it
DOT = 58  # radius of the touch

def defs(p, dark):
    if dark:
        bg = f'<radialGradient id="{p}bg" cx="512" cy="184" r="973" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#1d2346"/><stop offset=".55" stop-color="#0b0d18"/><stop offset="1" stop-color="#050609"/></radialGradient>'
        ring = '<stop offset="0" stop-color="#5f8dff"/><stop offset=".4" stop-color="#9a6bff"/><stop offset=".7" stop-color="#ff5a2a"/><stop offset="1" stop-color="#ffb46b"/>'
        more = (f'<filter id="{p}g2" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="12"/></filter>'
                f'<radialGradient id="{p}dot" cx=".4" cy=".35" r=".7"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#ffe2d4"/></radialGradient>'
                f'<radialGradient id="{p}halo"><stop offset="0" stop-color="#fff" stop-opacity=".55"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>'
                f'<linearGradient id="{p}hl" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".9"/><stop offset=".35" stop-color="#fff" stop-opacity="0"/></linearGradient>')
        blur = 34
    else:
        bg = f'<radialGradient id="{p}bg" cx="512" cy="0" r="1126" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#e3e6ee"/></radialGradient>'
        ring = '<stop offset="0" stop-color="#4f7bff"/><stop offset=".45" stop-color="#9b5cff"/><stop offset=".75" stop-color="#ff4d2a"/><stop offset="1" stop-color="#ffa64d"/>'
        more = (f'<filter id="{p}ds" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="14" stdDeviation="16" flood-color="#1a1d2e" flood-opacity=".35"/></filter>'
                f'<linearGradient id="{p}glass" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#dfe6ff" stop-opacity=".9"/><stop offset=".5" stop-color="#f1e9ff" stop-opacity=".5"/><stop offset="1" stop-color="#ffe2d2" stop-opacity=".9"/></linearGradient>'
                f'<linearGradient id="{p}hl" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff"/><stop offset=".4" stop-color="#fff" stop-opacity="0"/></linearGradient>')
        blur = 30
    return (f'<defs><clipPath id="{p}tile"><rect width="1024" height="1024" rx="232"/></clipPath>{bg}'
            f'<linearGradient id="{p}ring" x1="0" y1="0" x2="1" y2="1">{ring}</linearGradient>'
            f'<filter id="{p}g1" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="{blur}"/></filter>{more}</defs>')

def parts(p, dark):
    BG = f'<rect x="-256" y="-256" width="1536" height="1536" fill="url(#{p}bg)"/>'
    R = 'x="322" y="192" width="380" height="640" rx="110"'
    if dark:  # a neon phone outline, its glow, and the agent's touch
        mark = f'''<rect {R} fill="none" stroke="url(#{p}ring)" stroke-width="{SW*2.05:g}" opacity=".55" filter="url(#{p}g1)"/>
<rect {R} fill="none" stroke="url(#{p}ring)" stroke-width="{SW*1.2:g}" opacity=".9" filter="url(#{p}g2)"/>
<rect {R} fill="none" stroke="url(#{p}ring)" stroke-width="{SW}"/>
<rect x="{322-SW/2+2:g}" y="{192-SW/2+2:g}" width="{380+SW-4:g}" height="{640+SW-4:g}" rx="{110+SW/2-2:g}" fill="none" stroke="url(#{p}hl)" stroke-width="5"/>
<circle cx="{620-SW/2:g}" cy="{714-SW/2:g}" r="{DOT*2.7:g}" fill="url(#{p}halo)"/>
<circle cx="{620-SW/2:g}" cy="{714-SW/2:g}" r="{DOT}" fill="url(#{p}dot)"/>'''
    else:  # the same outline over a pane of tinted glass
        mark = f'''<rect {R} fill="none" stroke="url(#{p}ring)" stroke-width="{SW*1.75:g}" opacity=".5" filter="url(#{p}g1)"/>
<rect {R} fill="url(#{p}glass)"/>
<rect {R} fill="none" stroke="url(#{p}ring)" stroke-width="{SW+2}"/>
<rect x="{322+SW/2+7:g}" y="{192+SW/2+7:g}" width="{380-SW-14:g}" height="{640-SW-14:g}" rx="{110-SW/2-7:g}" fill="none" stroke="url(#{p}hl)" stroke-width="5"/>
<circle cx="{620-SW/2:g}" cy="{714-SW/2:g}" r="{DOT+2}" fill="#0d0f16" filter="url(#{p}ds)"/>'''
    FG = f'<g transform="translate(512 512) scale({K}) translate(-512 -512)">\n{mark}\n</g>'
    return BG, FG

HEAD = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" width="{w}" height="{w}">\n'
def w(name, body, d, vb='0 0 1024 1024', size=1024):
    open(name, 'w', encoding='utf8', newline='\n').write(HEAD.format(vb=vb, w=size) + d + '\n' + body + '\n</svg>\n')

if __name__ == '__main__':
    import sys
    if len(sys.argv) > 1: SW, DOT = float(sys.argv[1]), float(sys.argv[2])
    for name, dark, note in (('icon', True, 'neon glass'), ('icon-light', False, 'paper glass')):
        d = defs('', dark); BG, FG = parts('', dark)
        w(f'{name}.svg', f'<!-- OpenPhoneUse, {note}: a phone outline touched by the agent. -->\n<g clip-path="url(#tile)">\n{BG}\n{FG}\n</g>', d)
        # Adaptive layers: the 1024 tile is the 72dp visible window of the 108dp canvas.
        w(f'{name}-bg.svg', BG, d, '-256 -256 1536 1536', 432)
        w(f'{name}-fg.svg', FG, d, '-256 -256 1536 1536', 432)
    syms = []
    for sid, dark in (('logo-d', True), ('logo-l', False)):
        p = sid + '-'; BG, FG = parts(p, dark)
        syms.append(f'<symbol id="{sid}" viewBox="0 0 1024 1024">{defs(p, dark)}<g clip-path="url(#{p}tile)">{BG}{FG}</g></symbol>'.replace('\n', ''))
    open('symbols.html', 'w', encoding='utf8', newline='\n').write('\n'.join(syms))
