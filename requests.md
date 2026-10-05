# FORMULA:
```html
	<div class="div-root">
    <h1>Some Shitty Title - External style</h1>
    <p>Some paragraph</p>
    <p class="example-formula">
        `sum_(i=1)^n i^3=((n(n+1))/2)^2`
    </p>
    <p class="num-sys-formula">
        <strong>
            `x=ubrace(sum_(i=0)^(|__log_bn__|) v(s_i)b^i)_(n in NN) + ubrace(sum_(i=1)^oo v(s_i)b^(-i))_(q in [0,1) sub RR)`
        </strong>
    </p>
    <p class="fourier-formula">    
        `F(omega) = int_{-oo}^{oo} f(t) e^{-i omega t} dt`
    </p>
    </div>
```

# STYLE:
```css
    body {
        padding:0; 
        margin:0; 
        border:0;
    }
	.div-root {
        padding: 20px; 
        font-family: Arial, sans-serif; 
        -webkit-font-smoothing: antialiased; 
        -moz-osx-font-smoothing: grayscale; 
        background-color: #303030; 
        color: white;
    }
    .example-formula {
        font-size:18px;
    }
    .num-sys-formula {
        color: rgb(207, 123, 123); 
        font-size:25px;
    }
    .fourier-formula {
        color: rgb(59, 201, 212); 
        font-size:25px;
    }
```

	
# STYLE-REFS:
```
```
			
		