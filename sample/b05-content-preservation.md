# B05 content preservation sample

Convert this DOCX to Markdown and compare it with b05\-content\-preservation&#46;md&#46;

Expected warnings&#58; one unsupported DATE field and one unsupported Word bookmark target&#46;

## 01 Mixed text and repeated images

LEFT![이미지](<images/image_1.png>)MIDDLE![이미지](<images/image_1.png>)RIGHT

## 02 Manual breaks and tabs

A  
B&#9;C<br /><br />

&#9;

## 03 Literal text is not Markdown formatting

\*literal\* and \*\*not bold\*\*

\`not code\` \[not a link\]\(https&#58;//example&#46;com\)

\# not a heading

1&#46; not a numbered list

\- not a bullet

\> not a quote

\<img src=x\> \&amp; \| literal pipe

\\path\\\[brackets\]

&#32;&#32;&#32;&#32;four leading spaces

two trailing spaces&#32;&#32;

https&#58;//example&#46;com user&#64;example&#46;com www&#46;example&#46;com

## 04 Adjacent formatting and code whitespace

<strong>AB</strong><em>C</em><strong><em>D</em></strong>&#32;E

Before&#32;&#32;**Bold with edge spaces**&#32;&#32;After

<del><strong><em><code>&#32;&#96;a&#124;b&#96;&#92;&#32;</code></em></strong></del>

## 05 Hyperlink labels addresses and images

[<strong>\[Label\] \*literal\*</strong>](<https://example.com/a(b)?x=1&amp;label=%5Bok%5D>)

<a href="https://example.com/a(b)?q=&amp;copy;&amp;x=1">Entity\-looking URL must not change</a>

[LINK\-LEFT](<https://example.com>)[![이미지](<images/image_1.png>)](<https://example.com>)[LINK\-RIGHT](<https://example.com>)

## 06 Table pipes code tabs and paragraph boundaries


| A\|B | \*literal header\* |
| --- | --- |
| LEFT\|<br />&#9;RIGHT<br />Next paragraph in the same cell | <code>&#96;a&#124;b&#96;&#92;</code> |

## 07 Mixed list items and the body boundary

10. \*LIST\-LEFT\*![이미지](<images/image_1.png>)  
    &#9;\[LIST\-RIGHT\]
    - \# Literal child
1. Next parent item

Outside the list&#58; \*still literal\*&#46;

## 08 A heading with a manual line break<br />Still the same heading

## 09 Cached field result and an expected warning

Before field&#58; 2026\-10\-27 &#58;after field

## 10 Word bookmark and an expected warning

Bookmark target text

[Jump to the Word bookmark](<#b05_target>)
