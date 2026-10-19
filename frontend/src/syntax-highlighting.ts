import { HighlightStyle } from '@codemirror/language';
import { tags } from '@lezer/highlight';

// Shared token rules; styles.css supplies the Builder palette for both themes.
export const sourceHighlightStyle = HighlightStyle.define([
    { tag: [tags.keyword, tags.tagName], class: 'source-keyword' },
    { tag: [tags.string, tags.attributeValue], class: 'source-string' },
    { tag: [tags.number, tags.atom], class: 'source-number' },
    { tag: [tags.attributeName, tags.propertyName], class: 'source-property' },
    { tag: tags.comment, class: 'source-comment' },
    { tag: tags.operator, class: 'source-operator' },
]);
