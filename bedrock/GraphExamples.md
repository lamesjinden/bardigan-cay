{:card/type :markdown}

In addition to specifying a card type of `:graph`, a `:data` key must be present in the leading map literal. 
The value corresponding to `:data` is passed directly through to `Plotly.js`.
Therefore, the `:data` value must conform to the `Plotly.js` [API](https://plotly.com/javascript/reference/index/) for `data` (i.e. an array of 'traces').


Optionally, a `:layout` key may be specified. The corresponding value will, also, be passed directly through to `Plotly.js`.
A default `layout` configuration is merged onto (i.e. the default overrides a provided `layout` configuration _when_ keys conflict).
The default `layout` specifies the following configurations:
* `autosize` is configured to 'true'
* `paper_bgcolor` & `plot_bgcolor` are set to 'transparent'
* `font.color`

----

{:card/type :graph
 :data [{:x [2 4 6 8 10]
         :y [10 20 30 40 50]
         :type "line"
         :name "data A"}
        {:x [4 8 12 16 20]
         :y [10 20 30 40 50]
         :type "line"
         :name "data B"}
        {:x [2 4 6 8 10]
         :y [10 20 30 40 50]
         :type "bar"
         :name "data C"}]}

----

{:card/type :graph
 :data [{:values [19 26 55]
         :labels ["Residential" "Non-Residential" "Utility"]
         :type "pie"}]}

----

{:card/type :graph
   :data [{:x [1 2 3 4]
           :y [10 11 12 13]
           :mode "markers"
           :marker {:color ["hsl(0,100,40)" "hsl(33,100,40)" "hsl(66,100,40)" "hsl(99,100,40)"]
                    :size [12 22 32 42]
                    :opacity [0.6 0.7 0.8 0.9]}
           :type "scatter"}
          {:x [1 2 3 4]
           :y [11 12 13 14]
           :mode "markers"
           :marker {:color "rgb(31, 119, 180)"
                    :size 18
                    :symbol ["circle" "square" "diamond" "cross"]}
           :type "scatter"}
          {:x [1 2 3 4]
           :y [12 13 14 15]
           :mode "markers"
           :marker {:size 18
                    :line {:color ["rgb(120,120,120)" "rgb(120,120,120)" "red" "rgb(120,120,120)"]
                           :width [2 2 6 2]}}
           :type "scatter"}]}