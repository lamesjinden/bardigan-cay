This is an example of current Work In Progress on transclusion.



The following card transcludes a card from the pages BardiganCay, PatterningSupportInBardiganCay and VideoEmbedding

----
:transclude

{:from "BardiganCay" 
 :type :Markdown
 :ids ["1546b50a-36db-50d5-9529-d74a9a18beca" ]
}

----
:transclude

{:from "PatterningSupportInBardiganCay"
 :type :Markdown
 :ids ["3e3b45fa-936c-5a33-a9f3-fa441a71be20" "1c0f1620-b819-5742-abb0-225f969b4082"]
}

-------
:transclude

{:from "VideoEmbedding"
 :type :Markdown
:ids ["3cd30359-e6cb-501d-9f82-d6095a65a01e" ]
}

----

### Hint

There's an easy way to grab a card for transclusion. Open up the [[CardBar]] at the bottom of the card, and you'll see some data associated with it, including the hash, outlined in a box. Click this outlined hash to copy the full code for a transclusion of that card, to the clipboard. Now you can go to another page and paste it in.
