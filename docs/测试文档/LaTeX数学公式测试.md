# LaTeX 数学公式渲染测试

测试 LaTeX 数学公式的渲染效果，覆盖行内公式 `$...$` 和块级公式 `$$...$$`。

---

## 1. 行内公式（Inline）

### 基本运算

欧拉公式：$e^{i\pi} + 1 = 0$

勾股定理：$a^2 + b^2 = c^2$

二次方程求根公式：$x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}$

### 分数与根号

黄金比例：$\varphi = \frac{1 + \sqrt{5}}{2}$

连分数：$x = \cfrac{1}{1 + \cfrac{1}{1 + \cfrac{1}{1 + \cdots}}}$

### 上下标与特殊符号

求和：$S_n = \sum_{i=1}^{n} x_i$

积分：$\int_{0}^{\infty} e^{-x^2} dx = \frac{\sqrt{\pi}}{2}$

极限：$\lim_{x \to 0} \frac{\sin x}{x} = 1$

### 希腊字母

$\alpha, \beta, \gamma, \delta, \epsilon, \theta, \lambda, \mu, \pi, \sigma, \phi, \omega$

$\Gamma, \Delta, \Theta, \Lambda, \Pi, \Sigma, \Phi, \Omega$

### 集合与逻辑

全集：$A \cup B = \{x \mid x \in A \text{ 或 } x \in B\}$

存在任意：$\forall \epsilon > 0, \exists \delta > 0$ 使得 $|x - a| < \delta \implies |f(x) - L| < \epsilon$

---

## 2. 块级公式（Display）

### 矩阵

$$
A = \begin{pmatrix}
a_{11} & a_{12} & \cdots & a_{1n} \\
a_{21} & a_{22} & \cdots & a_{2n} \\
\vdots & \vdots & \ddots & \vdots \\
a_{m1} & a_{m2} & \cdots & a_{mn}
\end{pmatrix}
$$

### 行列式

$$
\det(A) = \begin{vmatrix}
a & b & c \\
d & e & f \\
g & h & i
\end{vmatrix} = aei + bfg + cdh - ceg - bdi - afh
$$

### 分段函数

$$
f(x) = \begin{cases}
x^2, & x \geq 0 \\
-x^2, & x < 0
\end{cases}
$$

### 多行对齐

$$
\begin{aligned}
\nabla \cdot \mathbf{E} &= \frac{\rho}{\epsilon_0} \\
\nabla \cdot \mathbf{B} &= 0 \\
\nabla \times \mathbf{E} &= -\frac{\partial \mathbf{B}}{\partial t} \\
\nabla \times \mathbf{B} &= \mu_0\mathbf{J} + \mu_0\epsilon_0\frac{\partial \mathbf{E}}{\partial t}
\end{aligned}
$$

### 求和与积分

$$
\sum_{n=1}^{\infty} \frac{1}{n^2} = \frac{\pi^2}{6}
$$

$$
\oint_{\partial S} \mathbf{F} \cdot d\mathbf{r} = \iint_{S} (\nabla \times \mathbf{F}) \cdot d\mathbf{S}
$$

---

## 3. 复杂场景

### 物理公式

爱因斯坦质能方程：

$$
E = mc^2
$$

薛定谔方程：

$$
i\hbar\frac{\partial}{\partial t}\Psi(\mathbf{r}, t) = \left[-\frac{\hbar^2}{2m}\nabla^2 + V(\mathbf{r}, t)\right]\Psi(\mathbf{r}, t)
$$

### 概率统计

正态分布概率密度函数：

$$
f(x) = \frac{1}{\sigma\sqrt{2\pi}} e^{-\frac{(x-\mu)^2}{2\sigma^2}}
$$

贝叶斯定理：

$$
P(A|B) = \frac{P(B|A) \cdot P(A)}{P(B)}
$$

### 化学方程式

$$
2H_2 + O_2 \xrightarrow{\text{点燃}} 2H_2O
$$

---

## 4. 混合内容（公式嵌入段落）

在机器学习中，假设函数通常表示为 $h_\theta(x) = \theta_0 + \theta_1x_1 + \theta_2x_2 + \cdots + \theta_nx_n$，而代价函数为：

$$
J(\theta) = \frac{1}{2m} \sum_{i=1}^{m} (h_\theta(x^{(i)}) - y^{(i)})^2
$$

梯度下降的更新规则为 $\theta_j := \theta_j - \alpha \frac{\partial}{\partial \theta_j} J(\theta)$，其中 $\alpha$ 是学习率。当 $\alpha$ 过大时，可能导致 $J(\theta)$ 不收敛；当 $\alpha$ 过小时，收敛速度会很慢。

---

## 5. 边界情况

### 空公式
$$
$$

### 超长公式

$$
f(x) = a_0 + \sum_{n=1}^{\infty} \left( a_n \cos \frac{n\pi x}{L} + b_n \sin \frac{n\pi x}{L} \right) = \frac{1}{2\pi} \int_{-\infty}^{\infty} e^{i\omega x} \left( \int_{-\infty}^{\infty} f(t) e^{-i\omega t} dt \right) d\omega
$$

### 嵌套花括号

$$
\max_{x \in \mathbb{R}^n} \left\{ c^T x \mid Ax \leq b, x \geq 0 \right\}
$$

### 特殊符号

箭头：$\to, \implies, \iff, \mapsto, \rightarrow, \Rightarrow, \Leftrightarrow$

运算符：$\pm, \times, \div, \cdot, \circ, \oplus, \otimes, \odot$

关系：$\leq, \geq, \neq, \approx, \equiv, \sim, \propto$

帽子：$\hat{x}, \bar{x}, \tilde{x}, \vec{x}, \dot{x}, \ddot{x}$

---

> **说明**：当前版本尚未实现 LaTeX 渲染，以上公式将以原始 Markdown 文本形式显示。实现后应使用 KaTeX 或 MathJax 等引擎渲染为数学符号。