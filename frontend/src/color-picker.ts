import { Decoration, EditorView, ViewPlugin, WidgetType } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import type { Range } from '@codemirror/state';

function openColorPanel(picker: HTMLInputElement, apply: () => void) {
  const original = picker.value;
  const panel = document.createElement('div');
  panel.className = 'color-panel';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-label', 'CSS color picker');
  const canvas = document.createElement('canvas');
  canvas.width = 200;
  canvas.height = 140;
  canvas.setAttribute('aria-label', 'Color palette');
  const hue = document.createElement('input');
  hue.type = 'range';
  hue.min = '0';
  hue.max = '360';
  hue.setAttribute('aria-label', 'Color hue');
  const value = document.createElement('input');
  value.setAttribute('aria-label', 'Selected color');
  value.value = original;
  const hint = document.createElement('small');
  hint.textContent = 'Double-click a color or click outside to apply.';
  panel.append(canvas, hue, value, hint);
  document.body.append(panel);
  const rect = picker.getBoundingClientRect();
  panel.style.left = `${Math.max(8, Math.min(rect.left, innerWidth - 232))}px`;
  panel.style.top = `${Math.max(8, Math.min(rect.bottom + 6, innerHeight - panel.offsetHeight - 8))}px`;
  const context = canvas.getContext('2d')!;
  const rgb = original.match(/\w\w/g)!.map((part) => parseInt(part, 16) / 255);
  const max = Math.max(...rgb),
    min = Math.min(...rgb),
    delta = max - min;
  let h =
    delta === 0
      ? 0
      : max === rgb[0]
        ? ((rgb[1] - rgb[2]) / delta) % 6
        : max === rgb[1]
          ? (rgb[2] - rgb[0]) / delta + 2
          : (rgb[0] - rgb[1]) / delta + 4;
  h = (h * 60 + 360) % 360;
  let saturation = max === 0 ? 0 : delta / max;
  let brightness = max;
  hue.value = String(h);
  const draw = () => {
    context.fillStyle = `hsl(${hue.value} 100% 50%)`;
    context.fillRect(0, 0, 200, 140);
    const white = context.createLinearGradient(0, 0, 200, 0);
    white.addColorStop(0, '#fff');
    white.addColorStop(1, '#ffffff00');
    context.fillStyle = white;
    context.fillRect(0, 0, 200, 140);
    const black = context.createLinearGradient(0, 0, 0, 140);
    black.addColorStop(0, '#00000000');
    black.addColorStop(1, '#000');
    context.fillStyle = black;
    context.fillRect(0, 0, 200, 140);
  };
  const choose = () => {
    const channels = context.getImageData(
      Math.min(199, Math.round(saturation * 200)),
      Math.min(139, Math.round((1 - brightness) * 140)),
      1,
      1,
    ).data;
    value.value =
      '#' +
      Array.from(channels.slice(0, 3), (channel) => channel.toString(16).padStart(2, '0')).join('');
    picker.value = value.value;
  };
  draw();
  hue.addEventListener('input', () => {
    draw();
    choose();
  });
  canvas.addEventListener('pointerdown', (event) => {
    const bounds = canvas.getBoundingClientRect();
    saturation = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width));
    brightness = 1 - Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height));
    choose();
  });
  value.addEventListener('input', () => {
    if (/^#[\da-f]{6}$/i.test(value.value)) picker.value = value.value;
  });
  let closed = false;
  const close = (commit: boolean) => {
    if (closed) return;
    closed = true;
    document.removeEventListener('pointerdown', outside, true);
    document.removeEventListener('keydown', keyboard, true);
    panel.remove();
    if (commit) apply();
    else picker.value = original;
  };
  const outside = (event: PointerEvent) => {
    if (!panel.contains(event.target as Node) && event.target !== picker) close(true);
  };
  const keyboard = (event: KeyboardEvent) => {
    if (event.key === 'Escape' || event.key === 'Enter') {
      event.preventDefault();
      event.stopPropagation();
      close(event.key === 'Enter');
    }
  };
  panel.addEventListener('dblclick', () => close(true));
  document.addEventListener('pointerdown', outside, true);
  document.addEventListener('keydown', keyboard, true);
  value.focus();
  return () => close(false);
}

class ColorWidget extends WidgetType {
  private closePanel?: () => void;
  constructor(
    readonly color: string,
    readonly from: number,
    readonly to: number,
  ) {
    super();
  }

  eq(other: ColorWidget) {
    return this.color === other.color && this.from === other.from && this.to === other.to;
  }

  toDOM(view: EditorView) {
    const picker = document.createElement('input');
    picker.type = 'color';
    picker.className = 'cm-color-picker';
    picker.setAttribute('aria-label', `Change CSS color ${this.color}`);
    picker.title = `Change ${this.color}`;
    // Canvas resolves CSS colors to RGB and preserves their alpha channel.
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d')!;
    context.fillStyle = this.color;
    context.fillRect(0, 0, 1, 1);
    const [r, g, b, alpha] = context.getImageData(0, 0, 1, 1).data;
    const hex = (value: number) => value.toString(16).padStart(2, '0');
    picker.value = `#${hex(r)}${hex(g)}${hex(b)}`;
    const apply = () => {
      // Resolve the widget's current position, even after edits elsewhere.
      const from = view.posAtDOM(picker);
      if (view.state.doc.sliceString(from, from + this.color.length) !== this.color) return;
      view.dispatch({
        changes: {
          from,
          to: from + this.color.length,
          insert: picker.value + (alpha < 255 ? hex(alpha) : ''),
        },
        userEvent: 'input',
      });
    };
    picker.addEventListener('change', apply);
    picker.addEventListener('click', (event) => {
      event.preventDefault();
      this.closePanel?.();
      this.closePanel = openColorPanel(picker, apply);
    });
    return picker;
  }

  destroy() {
    this.closePanel?.();
  }

  ignoreEvent() {
    return true;
  }
}

function colorDecorations(view: EditorView) {
  const widgets: Range<Decoration>[] = [];
  for (const { from, to } of view.visibleRanges) {
    syntaxTree(view.state).iterate({
      from,
      to,
      enter(node) {
        if (!['ColorLiteral', 'ValueName', 'CallExpression'].includes(node.name)) return;
        const color = view.state.doc.sliceString(node.from, node.to);
        if (
          ['transparent', 'currentcolor', 'inherit', 'initial', 'unset', 'revert'].includes(
            color.toLowerCase(),
          )
        )
          return;
        if (!CSS.supports('color', color) || /var\(|env\(/i.test(color)) return;
        widgets.push(
          Decoration.widget({ widget: new ColorWidget(color, node.from, node.to), side: -1 }).range(
            node.from,
          ),
        );
        return false;
      },
    });
  }
  return Decoration.set(widgets, true);
}

export const colorPickers = ViewPlugin.fromClass(
  class {
    decorations;
    constructor(view: EditorView) {
      this.decorations = colorDecorations(view);
    }
    update(update: import('@codemirror/view').ViewUpdate) {
      if (
        update.docChanged ||
        update.viewportChanged ||
        syntaxTree(update.startState) !== syntaxTree(update.state)
      ) {
        this.decorations = colorDecorations(update.view);
      }
    }
  },
  { decorations: (plugin) => plugin.decorations },
);
