// Generated from tools/svg/browser-entry.js; run npm run build. Do not edit.
(() => {
  var __create = Object.create;
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __getProtoOf = Object.getPrototypeOf;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __commonJS = (cb, mod) => function __require() {
    return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
    // If the importer is in node compatibility mode or this is not an ESM
    // file that has been converted to a CommonJS file using a Babel-
    // compatible transform (i.e. "__esModule" has not been set), then set
    // "default" to the CommonJS "module.exports" for node compatibility.
    isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
    mod
  ));

  // node_modules/mathjax-full/js/util/Styles.js
  var require_Styles = __commonJS({
    "node_modules/mathjax-full/js/util/Styles.js"(exports) {
      "use strict";
      var __values = exports && exports.__values || function(o) {
        var s = typeof Symbol === "function" && Symbol.iterator, m = s && o[s], i = 0;
        if (m) return m.call(o);
        if (o && typeof o.length === "number") return {
          next: function() {
            if (o && i >= o.length) o = void 0;
            return { value: o && o[i++], done: !o };
          }
        };
        throw new TypeError(s ? "Object is not iterable." : "Symbol.iterator is not defined.");
      };
      var __read = exports && exports.__read || function(o, n) {
        var m = typeof Symbol === "function" && o[Symbol.iterator];
        if (!m) return o;
        var i = m.call(o), r, ar = [], e;
        try {
          while ((n === void 0 || n-- > 0) && !(r = i.next()).done) ar.push(r.value);
        } catch (error) {
          e = { error };
        } finally {
          try {
            if (r && !r.done && (m = i["return"])) m.call(i);
          } finally {
            if (e) throw e.error;
          }
        }
        return ar;
      };
      var __spreadArray = exports && exports.__spreadArray || function(to, from, pack) {
        if (pack || arguments.length === 2) for (var i = 0, l = from.length, ar; i < l; i++) {
          if (ar || !(i in from)) {
            if (!ar) ar = Array.prototype.slice.call(from, 0, i);
            ar[i] = from[i];
          }
        }
        return to.concat(ar || Array.prototype.slice.call(from));
      };
      Object.defineProperty(exports, "__esModule", { value: true });
      exports.Styles = void 0;
      var TRBL = ["top", "right", "bottom", "left"];
      var WSC = ["width", "style", "color"];
      function splitSpaces(text) {
        var parts = text.split(/((?:'[^']*'|"[^"]*"|,[\s\n]|[^\s\n])*)/g);
        var split = [];
        while (parts.length > 1) {
          parts.shift();
          split.push(parts.shift());
        }
        return split;
      }
      function splitTRBL(name) {
        var e_1, _a;
        var parts = splitSpaces(this.styles[name]);
        if (parts.length === 0) {
          parts.push("");
        }
        if (parts.length === 1) {
          parts.push(parts[0]);
        }
        if (parts.length === 2) {
          parts.push(parts[0]);
        }
        if (parts.length === 3) {
          parts.push(parts[1]);
        }
        try {
          for (var _b = __values(Styles2.connect[name].children), _c = _b.next(); !_c.done; _c = _b.next()) {
            var child = _c.value;
            this.setStyle(this.childName(name, child), parts.shift());
          }
        } catch (e_1_1) {
          e_1 = { error: e_1_1 };
        } finally {
          try {
            if (_c && !_c.done && (_a = _b.return)) _a.call(_b);
          } finally {
            if (e_1) throw e_1.error;
          }
        }
      }
      function combineTRBL(name) {
        var e_2, _a;
        var children = Styles2.connect[name].children;
        var parts = [];
        try {
          for (var children_1 = __values(children), children_1_1 = children_1.next(); !children_1_1.done; children_1_1 = children_1.next()) {
            var child = children_1_1.value;
            var part = this.styles[name + "-" + child];
            if (!part) {
              delete this.styles[name];
              return;
            }
            parts.push(part);
          }
        } catch (e_2_1) {
          e_2 = { error: e_2_1 };
        } finally {
          try {
            if (children_1_1 && !children_1_1.done && (_a = children_1.return)) _a.call(children_1);
          } finally {
            if (e_2) throw e_2.error;
          }
        }
        if (parts[3] === parts[1]) {
          parts.pop();
          if (parts[2] === parts[0]) {
            parts.pop();
            if (parts[1] === parts[0]) {
              parts.pop();
            }
          }
        }
        this.styles[name] = parts.join(" ");
      }
      function splitSame(name) {
        var e_3, _a;
        try {
          for (var _b = __values(Styles2.connect[name].children), _c = _b.next(); !_c.done; _c = _b.next()) {
            var child = _c.value;
            this.setStyle(this.childName(name, child), this.styles[name]);
          }
        } catch (e_3_1) {
          e_3 = { error: e_3_1 };
        } finally {
          try {
            if (_c && !_c.done && (_a = _b.return)) _a.call(_b);
          } finally {
            if (e_3) throw e_3.error;
          }
        }
      }
      function combineSame(name) {
        var e_4, _a;
        var children = __spreadArray([], __read(Styles2.connect[name].children), false);
        var value = this.styles[this.childName(name, children.shift())];
        try {
          for (var children_2 = __values(children), children_2_1 = children_2.next(); !children_2_1.done; children_2_1 = children_2.next()) {
            var child = children_2_1.value;
            if (this.styles[this.childName(name, child)] !== value) {
              delete this.styles[name];
              return;
            }
          }
        } catch (e_4_1) {
          e_4 = { error: e_4_1 };
        } finally {
          try {
            if (children_2_1 && !children_2_1.done && (_a = children_2.return)) _a.call(children_2);
          } finally {
            if (e_4) throw e_4.error;
          }
        }
        this.styles[name] = value;
      }
      var BORDER = {
        width: /^(?:[\d.]+(?:[a-z]+)|thin|medium|thick|inherit|initial|unset)$/,
        style: /^(?:none|hidden|dotted|dashed|solid|double|groove|ridge|inset|outset|inherit|initial|unset)$/
      };
      function splitWSC(name) {
        var e_5, _a, e_6, _b;
        var parts = { width: "", style: "", color: "" };
        try {
          for (var _c = __values(splitSpaces(this.styles[name])), _d = _c.next(); !_d.done; _d = _c.next()) {
            var part = _d.value;
            if (part.match(BORDER.width) && parts.width === "") {
              parts.width = part;
            } else if (part.match(BORDER.style) && parts.style === "") {
              parts.style = part;
            } else {
              parts.color = part;
            }
          }
        } catch (e_5_1) {
          e_5 = { error: e_5_1 };
        } finally {
          try {
            if (_d && !_d.done && (_a = _c.return)) _a.call(_c);
          } finally {
            if (e_5) throw e_5.error;
          }
        }
        try {
          for (var _e = __values(Styles2.connect[name].children), _f = _e.next(); !_f.done; _f = _e.next()) {
            var child = _f.value;
            this.setStyle(this.childName(name, child), parts[child]);
          }
        } catch (e_6_1) {
          e_6 = { error: e_6_1 };
        } finally {
          try {
            if (_f && !_f.done && (_b = _e.return)) _b.call(_e);
          } finally {
            if (e_6) throw e_6.error;
          }
        }
      }
      function combineWSC(name) {
        var e_7, _a;
        var parts = [];
        try {
          for (var _b = __values(Styles2.connect[name].children), _c = _b.next(); !_c.done; _c = _b.next()) {
            var child = _c.value;
            var value = this.styles[this.childName(name, child)];
            if (value) {
              parts.push(value);
            }
          }
        } catch (e_7_1) {
          e_7 = { error: e_7_1 };
        } finally {
          try {
            if (_c && !_c.done && (_a = _b.return)) _a.call(_b);
          } finally {
            if (e_7) throw e_7.error;
          }
        }
        if (parts.length) {
          this.styles[name] = parts.join(" ");
        } else {
          delete this.styles[name];
        }
      }
      var FONT = {
        style: /^(?:normal|italic|oblique|inherit|initial|unset)$/,
        variant: new RegExp("^(?:" + [
          "normal|none",
          "inherit|initial|unset",
          "common-ligatures|no-common-ligatures",
          "discretionary-ligatures|no-discretionary-ligatures",
          "historical-ligatures|no-historical-ligatures",
          "contextual|no-contextual",
          "(?:stylistic|character-variant|swash|ornaments|annotation)\\([^)]*\\)",
          "small-caps|all-small-caps|petite-caps|all-petite-caps|unicase|titling-caps",
          "lining-nums|oldstyle-nums|proportional-nums|tabular-nums",
          "diagonal-fractions|stacked-fractions",
          "ordinal|slashed-zero",
          "jis78|jis83|jis90|jis04|simplified|traditional",
          "full-width|proportional-width",
          "ruby"
        ].join("|") + ")$"),
        weight: /^(?:normal|bold|bolder|lighter|[1-9]00|inherit|initial|unset)$/,
        stretch: new RegExp("^(?:" + [
          "normal",
          "(?:(?:ultra|extra|semi)-)?condensed",
          "(?:(?:semi|extra|ulta)-)?expanded",
          "inherit|initial|unset"
        ].join("|") + ")$"),
        size: new RegExp("^(?:" + [
          "xx-small|x-small|small|medium|large|x-large|xx-large|larger|smaller",
          "[d.]+%|[d.]+[a-z]+",
          "inherit|initial|unset"
        ].join("|") + ")(?:/(?:normal|[d.+](?:%|[a-z]+)?))?$")
      };
      function splitFont(name) {
        var e_8, _a, e_9, _b;
        var parts = splitSpaces(this.styles[name]);
        var value = {
          style: "",
          variant: [],
          weight: "",
          stretch: "",
          size: "",
          family: "",
          "line-height": ""
        };
        try {
          for (var parts_1 = __values(parts), parts_1_1 = parts_1.next(); !parts_1_1.done; parts_1_1 = parts_1.next()) {
            var part = parts_1_1.value;
            value.family = part;
            try {
              for (var _c = (e_9 = void 0, __values(Object.keys(FONT))), _d = _c.next(); !_d.done; _d = _c.next()) {
                var name_1 = _d.value;
                if ((Array.isArray(value[name_1]) || value[name_1] === "") && part.match(FONT[name_1])) {
                  if (name_1 === "size") {
                    var _e = __read(part.split(/\//), 2), size = _e[0], height = _e[1];
                    value[name_1] = size;
                    if (height) {
                      value["line-height"] = height;
                    }
                  } else if (value.size === "") {
                    if (Array.isArray(value[name_1])) {
                      value[name_1].push(part);
                    } else {
                      value[name_1] = part;
                    }
                  }
                }
              }
            } catch (e_9_1) {
              e_9 = { error: e_9_1 };
            } finally {
              try {
                if (_d && !_d.done && (_b = _c.return)) _b.call(_c);
              } finally {
                if (e_9) throw e_9.error;
              }
            }
          }
        } catch (e_8_1) {
          e_8 = { error: e_8_1 };
        } finally {
          try {
            if (parts_1_1 && !parts_1_1.done && (_a = parts_1.return)) _a.call(parts_1);
          } finally {
            if (e_8) throw e_8.error;
          }
        }
        saveFontParts(name, value);
        delete this.styles[name];
      }
      function saveFontParts(name, value) {
        var e_10, _a;
        try {
          for (var _b = __values(Styles2.connect[name].children), _c = _b.next(); !_c.done; _c = _b.next()) {
            var child = _c.value;
            var cname = this.childName(name, child);
            if (Array.isArray(value[child])) {
              var values = value[child];
              if (values.length) {
                this.styles[cname] = values.join(" ");
              }
            } else if (value[child] !== "") {
              this.styles[cname] = value[child];
            }
          }
        } catch (e_10_1) {
          e_10 = { error: e_10_1 };
        } finally {
          try {
            if (_c && !_c.done && (_a = _b.return)) _a.call(_b);
          } finally {
            if (e_10) throw e_10.error;
          }
        }
      }
      function combineFont(_name) {
      }
      var Styles2 = (function() {
        function Styles3(cssText) {
          if (cssText === void 0) {
            cssText = "";
          }
          this.parse(cssText);
        }
        Object.defineProperty(Styles3.prototype, "cssText", {
          get: function() {
            var e_11, _a;
            var styles = [];
            try {
              for (var _b = __values(Object.keys(this.styles)), _c = _b.next(); !_c.done; _c = _b.next()) {
                var name_2 = _c.value;
                var parent_1 = this.parentName(name_2);
                if (!this.styles[parent_1]) {
                  styles.push(name_2 + ": " + this.styles[name_2] + ";");
                }
              }
            } catch (e_11_1) {
              e_11 = { error: e_11_1 };
            } finally {
              try {
                if (_c && !_c.done && (_a = _b.return)) _a.call(_b);
              } finally {
                if (e_11) throw e_11.error;
              }
            }
            return styles.join(" ");
          },
          enumerable: false,
          configurable: true
        });
        Styles3.prototype.set = function(name, value) {
          name = this.normalizeName(name);
          this.setStyle(name, value);
          if (Styles3.connect[name] && !Styles3.connect[name].combine) {
            this.combineChildren(name);
            delete this.styles[name];
          }
          while (name.match(/-/)) {
            name = this.parentName(name);
            if (!Styles3.connect[name])
              break;
            Styles3.connect[name].combine.call(this, name);
          }
        };
        Styles3.prototype.get = function(name) {
          name = this.normalizeName(name);
          return this.styles.hasOwnProperty(name) ? this.styles[name] : "";
        };
        Styles3.prototype.setStyle = function(name, value) {
          this.styles[name] = value;
          if (Styles3.connect[name] && Styles3.connect[name].children) {
            Styles3.connect[name].split.call(this, name);
          }
          if (value === "") {
            delete this.styles[name];
          }
        };
        Styles3.prototype.combineChildren = function(name) {
          var e_12, _a;
          var parent = this.parentName(name);
          try {
            for (var _b = __values(Styles3.connect[name].children), _c = _b.next(); !_c.done; _c = _b.next()) {
              var child = _c.value;
              var cname = this.childName(parent, child);
              Styles3.connect[cname].combine.call(this, cname);
            }
          } catch (e_12_1) {
            e_12 = { error: e_12_1 };
          } finally {
            try {
              if (_c && !_c.done && (_a = _b.return)) _a.call(_b);
            } finally {
              if (e_12) throw e_12.error;
            }
          }
        };
        Styles3.prototype.parentName = function(name) {
          var parent = name.replace(/-[^-]*$/, "");
          return name === parent ? "" : parent;
        };
        Styles3.prototype.childName = function(name, child) {
          if (child.match(/-/)) {
            return child;
          }
          if (Styles3.connect[name] && !Styles3.connect[name].combine) {
            child += name.replace(/.*-/, "-");
            name = this.parentName(name);
          }
          return name + "-" + child;
        };
        Styles3.prototype.normalizeName = function(name) {
          return name.replace(/[A-Z]/g, function(c) {
            return "-" + c.toLowerCase();
          });
        };
        Styles3.prototype.parse = function(cssText) {
          if (cssText === void 0) {
            cssText = "";
          }
          var PATTERN = this.constructor.pattern;
          this.styles = {};
          var parts = cssText.replace(PATTERN.comment, "").split(PATTERN.style);
          while (parts.length > 1) {
            var _a = __read(parts.splice(0, 3), 3), space = _a[0], name_3 = _a[1], value = _a[2];
            if (space.match(/[^\s\n]/))
              return;
            this.set(name_3, value);
          }
        };
        Styles3.pattern = {
          style: /([-a-z]+)[\s\n]*:[\s\n]*((?:'[^']*'|"[^"]*"|\n|.)*?)[\s\n]*(?:;|$)/g,
          comment: /\/\*[^]*?\*\//g
        };
        Styles3.connect = {
          padding: {
            children: TRBL,
            split: splitTRBL,
            combine: combineTRBL
          },
          border: {
            children: TRBL,
            split: splitSame,
            combine: combineSame
          },
          "border-top": {
            children: WSC,
            split: splitWSC,
            combine: combineWSC
          },
          "border-right": {
            children: WSC,
            split: splitWSC,
            combine: combineWSC
          },
          "border-bottom": {
            children: WSC,
            split: splitWSC,
            combine: combineWSC
          },
          "border-left": {
            children: WSC,
            split: splitWSC,
            combine: combineWSC
          },
          "border-width": {
            children: TRBL,
            split: splitTRBL,
            combine: null
          },
          "border-style": {
            children: TRBL,
            split: splitTRBL,
            combine: null
          },
          "border-color": {
            children: TRBL,
            split: splitTRBL,
            combine: null
          },
          font: {
            children: ["style", "variant", "weight", "stretch", "line-height", "size", "family"],
            split: splitFont,
            combine: combineFont
          }
        };
        return Styles3;
      })();
      exports.Styles = Styles2;
    }
  });

  // node_modules/html-to-image/es/util.js
  function resolveUrl(url, baseUrl) {
    if (url.match(/^[a-z]+:\/\//i)) {
      return url;
    }
    if (url.match(/^\/\//)) {
      return window.location.protocol + url;
    }
    if (url.match(/^[a-z]+:/i)) {
      return url;
    }
    const doc = document.implementation.createHTMLDocument();
    const base = doc.createElement("base");
    const a = doc.createElement("a");
    doc.head.appendChild(base);
    doc.body.appendChild(a);
    if (baseUrl) {
      base.href = baseUrl;
    }
    a.href = url;
    return a.href;
  }
  var uuid = /* @__PURE__ */ (() => {
    let counter = 0;
    const random = () => (
      // eslint-disable-next-line no-bitwise
      `0000${(Math.random() * 36 ** 4 << 0).toString(36)}`.slice(-4)
    );
    return () => {
      counter += 1;
      return `u${random()}${counter}`;
    };
  })();
  function toArray(arrayLike) {
    const arr = [];
    for (let i = 0, l = arrayLike.length; i < l; i++) {
      arr.push(arrayLike[i]);
    }
    return arr;
  }
  var styleProps = null;
  function getStyleProperties(options = {}) {
    if (styleProps) {
      return styleProps;
    }
    if (options.includeStyleProperties) {
      styleProps = options.includeStyleProperties;
      return styleProps;
    }
    styleProps = toArray(window.getComputedStyle(document.documentElement));
    return styleProps;
  }
  function px(node, styleProperty) {
    const win = node.ownerDocument.defaultView || window;
    const val = win.getComputedStyle(node).getPropertyValue(styleProperty);
    return val ? parseFloat(val.replace("px", "")) : 0;
  }
  function getNodeWidth(node) {
    const leftBorder = px(node, "border-left-width");
    const rightBorder = px(node, "border-right-width");
    return node.clientWidth + leftBorder + rightBorder;
  }
  function getNodeHeight(node) {
    const topBorder = px(node, "border-top-width");
    const bottomBorder = px(node, "border-bottom-width");
    return node.clientHeight + topBorder + bottomBorder;
  }
  function getImageSize(targetNode, options = {}) {
    const width = options.width || getNodeWidth(targetNode);
    const height = options.height || getNodeHeight(targetNode);
    return { width, height };
  }
  function createImage(url) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => {
        img.decode().then(() => {
          requestAnimationFrame(() => resolve(img));
        });
      };
      img.onerror = reject;
      img.crossOrigin = "anonymous";
      img.decoding = "async";
      img.src = url;
    });
  }
  async function svgToDataURL(svg) {
    return Promise.resolve().then(() => new XMLSerializer().serializeToString(svg)).then(encodeURIComponent).then((html) => `data:image/svg+xml;charset=utf-8,${html}`);
  }
  async function nodeToDataURL(node, width, height) {
    const xmlns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(xmlns, "svg");
    const foreignObject = document.createElementNS(xmlns, "foreignObject");
    svg.setAttribute("width", `${width}`);
    svg.setAttribute("height", `${height}`);
    svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
    foreignObject.setAttribute("width", "100%");
    foreignObject.setAttribute("height", "100%");
    foreignObject.setAttribute("x", "0");
    foreignObject.setAttribute("y", "0");
    foreignObject.setAttribute("externalResourcesRequired", "true");
    svg.appendChild(foreignObject);
    foreignObject.appendChild(node);
    return svgToDataURL(svg);
  }
  var isInstanceOfElement = (node, instance) => {
    if (node instanceof instance)
      return true;
    const nodePrototype = Object.getPrototypeOf(node);
    if (nodePrototype === null)
      return false;
    return nodePrototype.constructor.name === instance.name || isInstanceOfElement(nodePrototype, instance);
  };

  // node_modules/html-to-image/es/clone-pseudos.js
  function formatCSSText(style) {
    const content = style.getPropertyValue("content");
    return `${style.cssText} content: '${content.replace(/'|"/g, "")}';`;
  }
  function formatCSSProperties(style, options) {
    return getStyleProperties(options).map((name) => {
      const value = style.getPropertyValue(name);
      const priority = style.getPropertyPriority(name);
      return `${name}: ${value}${priority ? " !important" : ""};`;
    }).join(" ");
  }
  function getPseudoElementStyle(className, pseudo, style, options) {
    const selector = `.${className}:${pseudo}`;
    const cssText = style.cssText ? formatCSSText(style) : formatCSSProperties(style, options);
    return document.createTextNode(`${selector}{${cssText}}`);
  }
  function clonePseudoElement(nativeNode, clonedNode, pseudo, options) {
    const style = window.getComputedStyle(nativeNode, pseudo);
    const content = style.getPropertyValue("content");
    if (content === "" || content === "none") {
      return;
    }
    const className = uuid();
    try {
      clonedNode.className = `${clonedNode.className} ${className}`;
    } catch (err) {
      return;
    }
    const styleElement = document.createElement("style");
    styleElement.appendChild(getPseudoElementStyle(className, pseudo, style, options));
    clonedNode.appendChild(styleElement);
  }
  function clonePseudoElements(nativeNode, clonedNode, options) {
    clonePseudoElement(nativeNode, clonedNode, ":before", options);
    clonePseudoElement(nativeNode, clonedNode, ":after", options);
  }

  // node_modules/html-to-image/es/mimes.js
  var WOFF = "application/font-woff";
  var JPEG = "image/jpeg";
  var mimes = {
    woff: WOFF,
    woff2: WOFF,
    ttf: "application/font-truetype",
    eot: "application/vnd.ms-fontobject",
    png: "image/png",
    jpg: JPEG,
    jpeg: JPEG,
    gif: "image/gif",
    tiff: "image/tiff",
    svg: "image/svg+xml",
    webp: "image/webp"
  };
  function getExtension(url) {
    const match = /\.([^./]*?)$/g.exec(url);
    return match ? match[1] : "";
  }
  function getMimeType(url) {
    const extension = getExtension(url).toLowerCase();
    return mimes[extension] || "";
  }

  // node_modules/html-to-image/es/dataurl.js
  function getContentFromDataUrl(dataURL) {
    return dataURL.split(/,/)[1];
  }
  function isDataUrl(url) {
    return url.search(/^(data:)/) !== -1;
  }
  function makeDataUrl(content, mimeType) {
    return `data:${mimeType};base64,${content}`;
  }
  async function fetchAsDataURL(url, init, process2) {
    const res = await fetch(url, init);
    if (res.status === 404) {
      throw new Error(`Resource "${res.url}" not found`);
    }
    const blob = await res.blob();
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = reject;
      reader.onloadend = () => {
        try {
          resolve(process2({ res, result: reader.result }));
        } catch (error) {
          reject(error);
        }
      };
      reader.readAsDataURL(blob);
    });
  }
  var cache = {};
  function getCacheKey(url, contentType, includeQueryParams) {
    let key = url.replace(/\?.*/, "");
    if (includeQueryParams) {
      key = url;
    }
    if (/ttf|otf|eot|woff2?/i.test(key)) {
      key = key.replace(/.*\//, "");
    }
    return contentType ? `[${contentType}]${key}` : key;
  }
  async function resourceToDataURL(resourceUrl, contentType, options) {
    const cacheKey = getCacheKey(resourceUrl, contentType, options.includeQueryParams);
    if (cache[cacheKey] != null) {
      return cache[cacheKey];
    }
    if (options.cacheBust) {
      resourceUrl += (/\?/.test(resourceUrl) ? "&" : "?") + (/* @__PURE__ */ new Date()).getTime();
    }
    let dataURL;
    try {
      const content = await fetchAsDataURL(resourceUrl, options.fetchRequestInit, ({ res, result }) => {
        if (!contentType) {
          contentType = res.headers.get("Content-Type") || "";
        }
        return getContentFromDataUrl(result);
      });
      dataURL = makeDataUrl(content, contentType);
    } catch (error) {
      dataURL = options.imagePlaceholder || "";
      let msg = `Failed to fetch resource: ${resourceUrl}`;
      if (error) {
        msg = typeof error === "string" ? error : error.message;
      }
      if (msg) {
        console.warn(msg);
      }
    }
    cache[cacheKey] = dataURL;
    return dataURL;
  }

  // node_modules/html-to-image/es/clone-node.js
  async function cloneCanvasElement(canvas) {
    const dataURL = canvas.toDataURL();
    if (dataURL === "data:,") {
      return canvas.cloneNode(false);
    }
    return createImage(dataURL);
  }
  async function cloneVideoElement(video, options) {
    if (video.currentSrc) {
      const canvas = document.createElement("canvas");
      const ctx = canvas.getContext("2d");
      canvas.width = video.clientWidth;
      canvas.height = video.clientHeight;
      ctx === null || ctx === void 0 ? void 0 : ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      const dataURL2 = canvas.toDataURL();
      return createImage(dataURL2);
    }
    const poster = video.poster;
    const contentType = getMimeType(poster);
    const dataURL = await resourceToDataURL(poster, contentType, options);
    return createImage(dataURL);
  }
  async function cloneIFrameElement(iframe, options) {
    var _a;
    try {
      if ((_a = iframe === null || iframe === void 0 ? void 0 : iframe.contentDocument) === null || _a === void 0 ? void 0 : _a.body) {
        return await cloneNode(iframe.contentDocument.body, options, true);
      }
    } catch (_b) {
    }
    return iframe.cloneNode(false);
  }
  async function cloneSingleNode(node, options) {
    if (isInstanceOfElement(node, HTMLCanvasElement)) {
      return cloneCanvasElement(node);
    }
    if (isInstanceOfElement(node, HTMLVideoElement)) {
      return cloneVideoElement(node, options);
    }
    if (isInstanceOfElement(node, HTMLIFrameElement)) {
      return cloneIFrameElement(node, options);
    }
    return node.cloneNode(isSVGElement(node));
  }
  var isSlotElement = (node) => node.tagName != null && node.tagName.toUpperCase() === "SLOT";
  var isSVGElement = (node) => node.tagName != null && node.tagName.toUpperCase() === "SVG";
  async function cloneChildren(nativeNode, clonedNode, options) {
    var _a, _b;
    if (isSVGElement(clonedNode)) {
      return clonedNode;
    }
    let children = [];
    if (isSlotElement(nativeNode) && nativeNode.assignedNodes) {
      children = toArray(nativeNode.assignedNodes());
    } else if (isInstanceOfElement(nativeNode, HTMLIFrameElement) && ((_a = nativeNode.contentDocument) === null || _a === void 0 ? void 0 : _a.body)) {
      children = toArray(nativeNode.contentDocument.body.childNodes);
    } else {
      children = toArray(((_b = nativeNode.shadowRoot) !== null && _b !== void 0 ? _b : nativeNode).childNodes);
    }
    if (children.length === 0 || isInstanceOfElement(nativeNode, HTMLVideoElement)) {
      return clonedNode;
    }
    await children.reduce((deferred, child) => deferred.then(() => cloneNode(child, options)).then((clonedChild) => {
      if (clonedChild) {
        clonedNode.appendChild(clonedChild);
      }
    }), Promise.resolve());
    return clonedNode;
  }
  function cloneCSSStyle(nativeNode, clonedNode, options) {
    const targetStyle = clonedNode.style;
    if (!targetStyle) {
      return;
    }
    const sourceStyle = window.getComputedStyle(nativeNode);
    if (sourceStyle.cssText) {
      targetStyle.cssText = sourceStyle.cssText;
      targetStyle.transformOrigin = sourceStyle.transformOrigin;
    } else {
      getStyleProperties(options).forEach((name) => {
        let value = sourceStyle.getPropertyValue(name);
        if (name === "font-size" && value.endsWith("px")) {
          const reducedFont = Math.floor(parseFloat(value.substring(0, value.length - 2))) - 0.1;
          value = `${reducedFont}px`;
        }
        if (isInstanceOfElement(nativeNode, HTMLIFrameElement) && name === "display" && value === "inline") {
          value = "block";
        }
        if (name === "d" && clonedNode.getAttribute("d")) {
          value = `path(${clonedNode.getAttribute("d")})`;
        }
        targetStyle.setProperty(name, value, sourceStyle.getPropertyPriority(name));
      });
    }
  }
  function cloneInputValue(nativeNode, clonedNode) {
    if (isInstanceOfElement(nativeNode, HTMLTextAreaElement)) {
      clonedNode.innerHTML = nativeNode.value;
    }
    if (isInstanceOfElement(nativeNode, HTMLInputElement)) {
      clonedNode.setAttribute("value", nativeNode.value);
    }
  }
  function cloneSelectValue(nativeNode, clonedNode) {
    if (isInstanceOfElement(nativeNode, HTMLSelectElement)) {
      const clonedSelect = clonedNode;
      const selectedOption = Array.from(clonedSelect.children).find((child) => nativeNode.value === child.getAttribute("value"));
      if (selectedOption) {
        selectedOption.setAttribute("selected", "");
      }
    }
  }
  function decorate(nativeNode, clonedNode, options) {
    if (isInstanceOfElement(clonedNode, Element)) {
      cloneCSSStyle(nativeNode, clonedNode, options);
      clonePseudoElements(nativeNode, clonedNode, options);
      cloneInputValue(nativeNode, clonedNode);
      cloneSelectValue(nativeNode, clonedNode);
    }
    return clonedNode;
  }
  async function ensureSVGSymbols(clone, options) {
    const uses = clone.querySelectorAll ? clone.querySelectorAll("use") : [];
    if (uses.length === 0) {
      return clone;
    }
    const processedDefs = {};
    for (let i = 0; i < uses.length; i++) {
      const use = uses[i];
      const id = use.getAttribute("xlink:href");
      if (id) {
        const exist = clone.querySelector(id);
        const definition = document.querySelector(id);
        if (!exist && definition && !processedDefs[id]) {
          processedDefs[id] = await cloneNode(definition, options, true);
        }
      }
    }
    const nodes = Object.values(processedDefs);
    if (nodes.length) {
      const ns = "http://www.w3.org/1999/xhtml";
      const svg = document.createElementNS(ns, "svg");
      svg.setAttribute("xmlns", ns);
      svg.style.position = "absolute";
      svg.style.width = "0";
      svg.style.height = "0";
      svg.style.overflow = "hidden";
      svg.style.display = "none";
      const defs = document.createElementNS(ns, "defs");
      svg.appendChild(defs);
      for (let i = 0; i < nodes.length; i++) {
        defs.appendChild(nodes[i]);
      }
      clone.appendChild(svg);
    }
    return clone;
  }
  async function cloneNode(node, options, isRoot) {
    if (!isRoot && options.filter && !options.filter(node)) {
      return null;
    }
    return Promise.resolve(node).then((clonedNode) => cloneSingleNode(clonedNode, options)).then((clonedNode) => cloneChildren(node, clonedNode, options)).then((clonedNode) => decorate(node, clonedNode, options)).then((clonedNode) => ensureSVGSymbols(clonedNode, options));
  }

  // node_modules/html-to-image/es/embed-resources.js
  var URL_REGEX = /url\((['"]?)([^'"]+?)\1\)/g;
  var URL_WITH_FORMAT_REGEX = /url\([^)]+\)\s*format\((["']?)([^"']+)\1\)/g;
  var FONT_SRC_REGEX = /src:\s*(?:url\([^)]+\)\s*format\([^)]+\)[,;]\s*)+/g;
  function toRegex(url) {
    const escaped = url.replace(/([.*+?^${}()|\[\]\/\\])/g, "\\$1");
    return new RegExp(`(url\\(['"]?)(${escaped})(['"]?\\))`, "g");
  }
  function parseURLs(cssText) {
    const urls = [];
    cssText.replace(URL_REGEX, (raw, quotation, url) => {
      urls.push(url);
      return raw;
    });
    return urls.filter((url) => !isDataUrl(url));
  }
  async function embed(cssText, resourceURL, baseURL, options, getContentFromUrl) {
    try {
      const resolvedURL = baseURL ? resolveUrl(resourceURL, baseURL) : resourceURL;
      const contentType = getMimeType(resourceURL);
      let dataURL;
      if (getContentFromUrl) {
        const content = await getContentFromUrl(resolvedURL);
        dataURL = makeDataUrl(content, contentType);
      } else {
        dataURL = await resourceToDataURL(resolvedURL, contentType, options);
      }
      return cssText.replace(toRegex(resourceURL), `$1${dataURL}$3`);
    } catch (error) {
    }
    return cssText;
  }
  function filterPreferredFontFormat(str, { preferredFontFormat }) {
    return !preferredFontFormat ? str : str.replace(FONT_SRC_REGEX, (match) => {
      while (true) {
        const [src, , format] = URL_WITH_FORMAT_REGEX.exec(match) || [];
        if (!format) {
          return "";
        }
        if (format === preferredFontFormat) {
          return `src: ${src};`;
        }
      }
    });
  }
  function shouldEmbed(url) {
    return url.search(URL_REGEX) !== -1;
  }
  async function embedResources(cssText, baseUrl, options) {
    if (!shouldEmbed(cssText)) {
      return cssText;
    }
    const filteredCSSText = filterPreferredFontFormat(cssText, options);
    const urls = parseURLs(filteredCSSText);
    return urls.reduce((deferred, url) => deferred.then((css) => embed(css, url, baseUrl, options)), Promise.resolve(filteredCSSText));
  }

  // node_modules/html-to-image/es/embed-images.js
  async function embedProp(propName, node, options) {
    var _a;
    const propValue = (_a = node.style) === null || _a === void 0 ? void 0 : _a.getPropertyValue(propName);
    if (propValue) {
      const cssString = await embedResources(propValue, null, options);
      node.style.setProperty(propName, cssString, node.style.getPropertyPriority(propName));
      return true;
    }
    return false;
  }
  async function embedBackground(clonedNode, options) {
    ;
    await embedProp("background", clonedNode, options) || await embedProp("background-image", clonedNode, options);
    await embedProp("mask", clonedNode, options) || await embedProp("-webkit-mask", clonedNode, options) || await embedProp("mask-image", clonedNode, options) || await embedProp("-webkit-mask-image", clonedNode, options);
  }
  async function embedImageNode(clonedNode, options) {
    const isImageElement = isInstanceOfElement(clonedNode, HTMLImageElement);
    if (!(isImageElement && !isDataUrl(clonedNode.src)) && !(isInstanceOfElement(clonedNode, SVGImageElement) && !isDataUrl(clonedNode.href.baseVal))) {
      return;
    }
    const url = isImageElement ? clonedNode.src : clonedNode.href.baseVal;
    const dataURL = await resourceToDataURL(url, getMimeType(url), options);
    await new Promise((resolve, reject) => {
      clonedNode.onload = resolve;
      clonedNode.onerror = options.onImageErrorHandler ? (...attributes) => {
        try {
          resolve(options.onImageErrorHandler(...attributes));
        } catch (error) {
          reject(error);
        }
      } : reject;
      const image = clonedNode;
      if (image.decode) {
        image.decode = resolve;
      }
      if (image.loading === "lazy") {
        image.loading = "eager";
      }
      if (isImageElement) {
        clonedNode.srcset = "";
        clonedNode.src = dataURL;
      } else {
        clonedNode.href.baseVal = dataURL;
      }
    });
  }
  async function embedChildren(clonedNode, options) {
    const children = toArray(clonedNode.childNodes);
    const deferreds = children.map((child) => embedImages(child, options));
    await Promise.all(deferreds).then(() => clonedNode);
  }
  async function embedImages(clonedNode, options) {
    if (isInstanceOfElement(clonedNode, Element)) {
      await embedBackground(clonedNode, options);
      await embedImageNode(clonedNode, options);
      await embedChildren(clonedNode, options);
    }
  }

  // node_modules/html-to-image/es/apply-style.js
  function applyStyle(node, options) {
    const { style } = node;
    if (options.backgroundColor) {
      style.backgroundColor = options.backgroundColor;
    }
    if (options.width) {
      style.width = `${options.width}px`;
    }
    if (options.height) {
      style.height = `${options.height}px`;
    }
    const manual = options.style;
    if (manual != null) {
      Object.keys(manual).forEach((key) => {
        style[key] = manual[key];
      });
    }
    return node;
  }

  // node_modules/html-to-image/es/embed-webfonts.js
  var cssFetchCache = {};
  async function fetchCSS(url) {
    let cache2 = cssFetchCache[url];
    if (cache2 != null) {
      return cache2;
    }
    const res = await fetch(url);
    const cssText = await res.text();
    cache2 = { url, cssText };
    cssFetchCache[url] = cache2;
    return cache2;
  }
  async function embedFonts(data, options) {
    let cssText = data.cssText;
    const regexUrl = /url\(["']?([^"')]+)["']?\)/g;
    const fontLocs = cssText.match(/url\([^)]+\)/g) || [];
    const loadFonts = fontLocs.map(async (loc) => {
      let url = loc.replace(regexUrl, "$1");
      if (!url.startsWith("https://")) {
        url = new URL(url, data.url).href;
      }
      return fetchAsDataURL(url, options.fetchRequestInit, ({ result }) => {
        cssText = cssText.replace(loc, `url(${result})`);
        return [loc, result];
      });
    });
    return Promise.all(loadFonts).then(() => cssText);
  }
  function parseCSS(source) {
    if (source == null) {
      return [];
    }
    const result = [];
    const commentsRegex = /(\/\*[\s\S]*?\*\/)/gi;
    let cssText = source.replace(commentsRegex, "");
    const keyframesRegex = new RegExp("((@.*?keyframes [\\s\\S]*?){([\\s\\S]*?}\\s*?)})", "gi");
    while (true) {
      const matches = keyframesRegex.exec(cssText);
      if (matches === null) {
        break;
      }
      result.push(matches[0]);
    }
    cssText = cssText.replace(keyframesRegex, "");
    const importRegex = /@import[\s\S]*?url\([^)]*\)[\s\S]*?;/gi;
    const combinedCSSRegex = "((\\s*?(?:\\/\\*[\\s\\S]*?\\*\\/)?\\s*?@media[\\s\\S]*?){([\\s\\S]*?)}\\s*?})|(([\\s\\S]*?){([\\s\\S]*?)})";
    const unifiedRegex = new RegExp(combinedCSSRegex, "gi");
    while (true) {
      let matches = importRegex.exec(cssText);
      if (matches === null) {
        matches = unifiedRegex.exec(cssText);
        if (matches === null) {
          break;
        } else {
          importRegex.lastIndex = unifiedRegex.lastIndex;
        }
      } else {
        unifiedRegex.lastIndex = importRegex.lastIndex;
      }
      result.push(matches[0]);
    }
    return result;
  }
  async function getCSSRules(styleSheets, options) {
    const ret = [];
    const deferreds = [];
    styleSheets.forEach((sheet) => {
      if ("cssRules" in sheet) {
        try {
          toArray(sheet.cssRules || []).forEach((item, index) => {
            if (item.type === CSSRule.IMPORT_RULE) {
              let importIndex = index + 1;
              const url = item.href;
              const deferred = fetchCSS(url).then((metadata) => embedFonts(metadata, options)).then((cssText) => parseCSS(cssText).forEach((rule) => {
                try {
                  sheet.insertRule(rule, rule.startsWith("@import") ? importIndex += 1 : sheet.cssRules.length);
                } catch (error) {
                  console.error("Error inserting rule from remote css", {
                    rule,
                    error
                  });
                }
              })).catch((e) => {
                console.error("Error loading remote css", e.toString());
              });
              deferreds.push(deferred);
            }
          });
        } catch (e) {
          const inline = styleSheets.find((a) => a.href == null) || document.styleSheets[0];
          if (sheet.href != null) {
            deferreds.push(fetchCSS(sheet.href).then((metadata) => embedFonts(metadata, options)).then((cssText) => parseCSS(cssText).forEach((rule) => {
              inline.insertRule(rule, inline.cssRules.length);
            })).catch((err) => {
              console.error("Error loading remote stylesheet", err);
            }));
          }
          console.error("Error inlining remote css file", e);
        }
      }
    });
    return Promise.all(deferreds).then(() => {
      styleSheets.forEach((sheet) => {
        if ("cssRules" in sheet) {
          try {
            toArray(sheet.cssRules || []).forEach((item) => {
              ret.push(item);
            });
          } catch (e) {
            console.error(`Error while reading CSS rules from ${sheet.href}`, e);
          }
        }
      });
      return ret;
    });
  }
  function getWebFontRules(cssRules) {
    return cssRules.filter((rule) => rule.type === CSSRule.FONT_FACE_RULE).filter((rule) => shouldEmbed(rule.style.getPropertyValue("src")));
  }
  async function parseWebFontRules(node, options) {
    if (node.ownerDocument == null) {
      throw new Error("Provided element is not within a Document");
    }
    const styleSheets = toArray(node.ownerDocument.styleSheets);
    const cssRules = await getCSSRules(styleSheets, options);
    return getWebFontRules(cssRules);
  }
  function normalizeFontFamily(font) {
    return font.trim().replace(/["']/g, "");
  }
  function getUsedFonts(node) {
    const fonts = /* @__PURE__ */ new Set();
    function traverse(node2) {
      const fontFamily = node2.style.fontFamily || getComputedStyle(node2).fontFamily;
      fontFamily.split(",").forEach((font) => {
        fonts.add(normalizeFontFamily(font));
      });
      Array.from(node2.children).forEach((child) => {
        if (child instanceof HTMLElement) {
          traverse(child);
        }
      });
    }
    traverse(node);
    return fonts;
  }
  async function getWebFontCSS(node, options) {
    const rules = await parseWebFontRules(node, options);
    const usedFonts = getUsedFonts(node);
    const cssTexts = await Promise.all(rules.filter((rule) => usedFonts.has(normalizeFontFamily(rule.style.fontFamily))).map((rule) => {
      const baseUrl = rule.parentStyleSheet ? rule.parentStyleSheet.href : null;
      return embedResources(rule.cssText, baseUrl, options);
    }));
    return cssTexts.join("\n");
  }
  async function embedWebFonts(clonedNode, options) {
    const cssText = options.fontEmbedCSS != null ? options.fontEmbedCSS : options.skipFonts ? null : await getWebFontCSS(clonedNode, options);
    if (cssText) {
      const styleNode = document.createElement("style");
      const sytleContent = document.createTextNode(cssText);
      styleNode.appendChild(sytleContent);
      if (clonedNode.firstChild) {
        clonedNode.insertBefore(styleNode, clonedNode.firstChild);
      } else {
        clonedNode.appendChild(styleNode);
      }
    }
  }

  // node_modules/html-to-image/es/index.js
  async function toSvg(node, options = {}) {
    const { width, height } = getImageSize(node, options);
    const clonedNode = await cloneNode(node, options, true);
    await embedWebFonts(clonedNode, options);
    await embedImages(clonedNode, options);
    applyStyle(clonedNode, options);
    const datauri = await nodeToDataURL(clonedNode, width, height);
    return datauri;
  }

  // complexity-limits.js
  var policy = globalThis.PermaFormulaComplexity;
  if (!policy || typeof policy !== "object") {
    throw new Error("Missing rendering complexity policy");
  }
  var limits = Object.freeze(policy);
  function validateMathInput(source) {
    if (source.length > limits.MATH_CHARS) {
      throw new Error("Math expression exceeds size limit");
    }
  }
  function validateTree(root, children, nodeLimit, depthLimit, label) {
    const pending = [[root, 0]];
    let count = 0;
    while (pending.length) {
      const [node, depth] = pending.pop();
      if (++count > nodeLimit || depth > depthLimit) {
        throw new Error(`${label} exceeds complexity limits`);
      }
      for (const child of children(node)) {
        pending.push([child, depth + 1]);
      }
    }
    return count;
  }
  function validateDom(root) {
    return validateTree(
      root,
      (node) => node.childNodes,
      limits.RENDER_NODES,
      limits.RENDER_DEPTH,
      "Rendered DOM"
    );
  }
  function validateDimensions(width, height) {
    if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 || width > limits.RENDER_DIMENSION || height > limits.RENDER_DIMENSION || width * height > limits.RENDER_AREA) {
      throw new Error("Rendered dimensions exceed limits");
    }
  }

  // export-styled-svg.js
  async function exportStyledSvg() {
    const source = document.body;
    validateDom(source);
    const dimensions = measureContent(source);
    validateDimensions(dimensions.width, dimensions.height);
    const dataUrl = await toSvg(source, {
      ...dimensions,
      skipFonts: true,
      filter: includeNode
    });
    if (dataUrl.length > limits.SVG_BYTES * 3 + 128) {
      throw new Error("SVG exceeds size limit");
    }
    const result = normalizeSvg(decodeSvg(dataUrl));
    if (result.length > limits.SVG_BYTES) {
      throw new Error("SVG exceeds size limit");
    }
    return result;
  }
  function measureContent(source) {
    const computed = getComputedStyle(source);
    const bounds = source.getBoundingClientRect();
    const height = Math.max(
      1,
      Math.ceil(
        bounds.height + Number.parseFloat(computed.marginTop) + Number.parseFloat(computed.marginBottom)
      )
    );
    const width = window.innerWidth;
    validateDimensions(
      Math.max(width, source.scrollWidth, bounds.width),
      Math.max(height, source.scrollHeight)
    );
    for (const element of source.querySelectorAll("*")) {
      const rectangle = element.getBoundingClientRect();
      if (![rectangle.x, rectangle.y, rectangle.width, rectangle.height].every(Number.isFinite) || Math.abs(rectangle.x) > limits.ELEMENT_POSITION || Math.abs(rectangle.y) > limits.ELEMENT_POSITION || rectangle.width > limits.RENDER_DIMENSION || rectangle.height > limits.RENDER_DIMENSION) {
        throw new Error("Element geometry exceeds limits");
      }
    }
    return {
      width,
      height,
      // Canvas dimensions include margins; keep the body's original content-box dimensions.
      style: { width: computed.width, height: computed.height }
    };
  }
  function includeNode(node) {
    return !["SCRIPT", "LINK", "STYLE"].includes(node.nodeName.toUpperCase());
  }
  function decodeSvg(dataUrl) {
    const prefix = "data:image/svg+xml;charset=utf-8,";
    if (!dataUrl.startsWith(prefix)) {
      throw new Error("Invalid SVG data URL");
    }
    const markup = decodeURIComponent(dataUrl.slice(prefix.length));
    const svg = new DOMParser().parseFromString(markup, "image/svg+xml");
    if (svg.querySelector("parsererror") || svg.documentElement.localName !== "svg" || svg.documentElement.namespaceURI !== "http://www.w3.org/2000/svg") {
      throw new Error("Invalid SVG document");
    }
    return svg;
  }
  function normalizeSvg(svg) {
    svg.querySelectorAll("foreignObject").forEach((node) => {
      node.removeAttribute("externalResourcesRequired");
    });
    svg.querySelectorAll("script,link,style").forEach((node) => node.remove());
    return new XMLSerializer().serializeToString(svg.documentElement);
  }

  // mathjax-safety.js
  var import_Styles = __toESM(require_Styles(), 1);
  var BEFORE_COMPILE = 15;
  var AFTER_COMPILE = 25;
  var INVALID_NODES = Symbol("PermaFormula invalid math attributes");
  function safeMathOptions({ discoverInputs = true } = {}) {
    return {
      safeOptions: {
        allow: { URLs: "safe", classes: "safe", cssIDs: "safe", styles: "safe" },
        safeProtocols: {
          https: true,
          http: false,
          file: false,
          javascript: false,
          data: false
        }
      },
      renderActions: {
        ...discoverInputs ? { checkMathInputs: [BEFORE_COMPILE, validateInputs, validateInput] } : {},
        validateCompiledMath: [AFTER_COMPILE, validateDocument, validateItem]
      }
    };
  }
  function validateInputs(document2) {
    let count = 0;
    for (const math of document2.math) {
      if (++count > limits.MATH_EXPRESSIONS) {
        throw new Error("Math expression count exceeds limit");
      }
      validateInput(math);
    }
  }
  function validateInput(math) {
    validateMathInput(math.math);
  }
  function validateDocument(document2) {
    let nodes = 0;
    for (const math of document2.math) {
      nodes += validateMath(math, document2);
      if (nodes > limits.MATH_NODES) {
        throw new Error("Math document exceeds complexity limits");
      }
    }
  }
  function validateMath(math, document2) {
    const count = validateTree(
      math.root,
      (node) => node.childNodes,
      limits.MATH_NODES,
      limits.MATH_DEPTH,
      "Compiled math"
    );
    math.root.walkTree((node) => {
      if (node.kind === "merror" || document2.safe[INVALID_NODES]?.has(node)) {
        throw new Error("Invalid math expression");
      }
      const attributes = node.attributes?.getAllAttributes();
      if (!attributes) {
        return;
      }
      for (const name of ["href", "src", "altimg"]) {
        if (attributes[name] && /[\u0000-\u0020\u007f]/u.test(attributes[name])) {
          throw new Error("Invalid math URL");
        }
      }
      if (attributes.fontfamily && // eslint-disable-next-line no-control-regex -- Reject controls in generated CSS values.
      /[;:{}()\\<>\u0000-\u001f\u007f]/u.test(attributes.fontfamily)) {
        throw new Error("Invalid math font family");
      }
    });
    document2.safe.sanitize(math, document2);
    math.root.walkTree((node) => {
      if (node.kind === "merror" || document2.safe[INVALID_NODES]?.has(node)) {
        throw new Error("Invalid math expression");
      }
    });
    return count;
  }
  function validateItem(math, document2) {
    validateMath(math, document2);
  }
  function installMathValidation(document2) {
    const safe = document2.safe;
    const invalidNodes = /* @__PURE__ */ new WeakSet();
    safe[INVALID_NODES] = invalidNodes;
    const validateNode = safe.sanitizeNode.bind(safe);
    safe.sanitizeNode = (node) => {
      try {
        const attributes = node.attributes.getAllAttributes();
        const original = { ...attributes };
        const expected = canonicalAttributes(original, document2.adaptor);
        validateNode(node);
        if (canonicalAttributes(attributes, document2.adaptor) !== expected) {
          throw new Error("Invalid math attributes");
        }
        Object.assign(attributes, original);
      } catch (error) {
        invalidNodes.add(node);
        throw error;
      }
    };
  }
  function canonicalAttributes(attributes, adaptor) {
    return JSON.stringify(
      Object.entries(attributes).map(([name, value]) => {
        if (name === "style") {
          const node = adaptor.node("div", { style: value });
          const styles = new import_Styles.Styles(adaptor.allStyles(node)).styles;
          return [name, Object.entries(styles).sort()];
        }
        if (name === "class") {
          return [name, String(value).trim().split(/\s+/).sort()];
        }
        return [name, String(value)];
      }).sort(([left], [right]) => left.localeCompare(right))
    );
  }

  // browser-assets.js
  async function waitForAssets(document2) {
    await Promise.all([document2.fonts.ready, ...Array.from(document2.images, waitForImage)]);
  }
  function waitForImage(image) {
    if (image.complete) {
      return image.naturalWidth ? Promise.resolve() : Promise.reject(imageError(image));
    }
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        image.removeEventListener("load", onLoad);
        image.removeEventListener("error", onError);
      };
      const onLoad = () => {
        cleanup();
        resolve();
      };
      const onError = () => {
        cleanup();
        reject(imageError(image));
      };
      image.addEventListener("load", onLoad, { once: true });
      image.addEventListener("error", onError, { once: true });
    });
  }
  function imageError(image) {
    return new Error(`Image failed: ${image.currentSrc || image.src}`);
  }

  // browser-entry.js
  var renderer = { state: "loading", svg: null, error: null };
  window.PermaFormulaRenderer = renderer;
  window.MathJax = {
    loader: { load: ["input/asciimath", "ui/safe"] },
    options: safeMathOptions(),
    startup: {
      ready() {
        window.MathJax.startup.defaultReady();
        installMathValidation(window.MathJax.startup.document);
      },
      pageReady: renderPage
    },
    svg: { fontCache: "local" },
    tex: { maxMacros: limits.TEX_MACROS, maxBuffer: limits.TEX_BUFFER }
  };
  window.addEventListener("error", handleScriptError, true);
  async function renderPage() {
    try {
      validateDom(document.body);
      await window.MathJax.startup.defaultPageReady();
      await waitForAssets(document);
      validateDom(document.body);
      if (document.querySelector("mjx-merror")) {
        throw new Error("Invalid math expression");
      }
      const svg = await exportStyledSvg();
      if (renderer.state !== "error") {
        renderer.svg = svg;
        renderer.state = "ready";
      }
    } catch (error) {
      failRender(error);
    }
  }
  function handleScriptError(event) {
    if (event.target instanceof HTMLScriptElement && event.target.id === "mathjax-script") {
      failRender(new Error("MathJax failed to load"));
    }
  }
  function failRender(error) {
    renderer.svg = null;
    renderer.error = String(error);
    renderer.state = "error";
  }
})();
