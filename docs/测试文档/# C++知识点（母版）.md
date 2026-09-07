# auto 与范围 for

## auto 关键字

auto 让编译器自动推断变量的类型：

auto x = 10;        // x 是 int
auto s = "hello";   // s 是 const char*
auto d = 3.14;      // d 是 double

## 范围 for 循环

用于遍历容器（vector、string、数组等）中的所有元素：

vector<int> v = {1, 2, 3, 4, 5};

// 方式1: 值拷贝
for (auto x : v) {
    cout << x << endl;  // x 是每个元素的副本
}

// 方式2: 引用（避免拷贝）
for (auto& x : v) {
    x *= 2;  // 可以修改原元素
}

// 方式3: 常量引用（只读）
for (const auto& x : v) {
    cout << x << endl;  // 不能修改，效率高
}

## 等价的传统写法

for (auto x : v)       等价于  for (int i = 0; i < v.size(); i++) { auto x = v[i]; }
for (auto& x : v)      等价于  for (int i = 0; i < v.size(); i++) { auto& x = v[i]; }


# 文件流读写

## 流的统一性

C++ 的核心设计思想：**所有输入流使用相同的 >> 操作，所有输出流使用相同的 << 操作**。

| 操作 | 键盘输入 | 文件输入 |
|------|---------|---------|
| 读取数据 | `cin >> x` | `fin >> x` |
| 读取一行 | `getline(cin, s)` | `getline(fin, s)` |

| 操作 | 屏幕输出 | 文件输出 |
|------|---------|---------|
| 写入数据 | `cout << x` | `fout << x` |

## ifstream（输入文件流）

```cpp
#include <fstream>

ifstream fin("input.txt");   // 打开文件用于读取
if (!fin) {
    cout << "文件打开失败" << endl;
    return 1;
}

int n;
fin >> n;                    // 和 cin >> n 用法完全相同

string line;
getline(fin, line);          // 和 getline(cin, line) 用法完全相同

fin.close();                 // 关闭文件
```

## ofstream（输出文件流）

```cpp
ofstream fout("output.txt"); // 打开文件用于写入
if (!fout) {
    cout << "文件打开失败" << endl;
    return 1;
}

fout << "Hello" << endl;     // 和 cout << 用法完全相同

fout.close();                // 关闭文件
```

## 完整示例：从cin到文件

**cin 版本：**
```cpp
int a, b;
cin >> a >> b;
cout << a + b << endl;
```

**文件版本（只改流对象）：**
```cpp
ifstream fin("input.txt");
ofstream fout("output.txt");
int a, b;
fin >> a >> b;         // cin → fin
fout << a + b << endl; // cout → fout
fin.close();
fout.close();
```


# 引用参数传递

## 什么是引用

引用是变量的别名，操作引用就是操作原变量：

```cpp
int x = 10;
int& ref = x;    // ref 是 x 的引用（别名）
ref = 20;        // x 也变成 20
```

声明引用时使用 `&` 符号，引用必须在声明时初始化，且不能重新绑定到其他变量。

## 值传递 vs 引用传递

函数参数的传递方式决定了函数内部能否修改调用者的变量。

**值传递**（不改变原变量）：

```cpp
void doubleValue(int x) {
    x = x * 2;    // x 是副本，修改不影响原变量
}

int a = 5;
doubleValue(a);   // a 仍然是 5
```

**引用传递**（改变原变量）：

```cpp
void doubleValue(int& x) {
    x = x * 2;    // x 是原变量的引用，修改会影响原变量
}

int a = 5;
doubleValue(a);   // a 变成 10
```

两者的关键区别在于参数类型：`int x` 接收的是值的副本，`int& x` 接收的是原变量的引用。

## 交换两个变量

交换两个变量的值是引用传递的经典应用场景。

要交换两个变量 `a` 和 `b` 的值，你需要借助一个**临时变量**来暂存其中一个值，否则在赋值过程中会丢失数据。

基本思路如下：

1. 用临时变量保存其中一个变量的值
2. 将另一个变量的值赋给第一个变量
3. 将临时变量中保存的值赋给第二个变量

关键在于：如果你想让函数真正交换调用者的两个变量，函数参数必须使用**引用传递**。如果使用值传递，函数只会交换副本，原变量不会发生任何变化。

请根据以上提示，自行编写一个 `swap` 函数，并在 `main` 函数中测试验证交换是否成功。



# getline 分隔符解析

## getline 三种用法

```cpp
string s;
getline(cin, s);        // 读取整行（到换行符为止）
getline(cin, s, ':');   // 读取到 ':' 为止
getline(cin, s, '/');   // 读取到 '/' 为止
```

## 分隔符的原理

`getline(stream, str, delimiter)` 的行为：
- 从流中逐字符读取
- 遇到 `delimiter` 时停止
- **分隔符本身被消费但不存入字符串**
- 后续的读取从分隔符之后继续

## 示例：解析路径

假设输入为 `/home/user/docs`，程序执行：

```cpp
string p1, p2, p3;
getline(cin, p1, '/');   // p1 = ""  （开头就是/，读到空字符串）
getline(cin, p2, '/');   // p2 = "home"
getline(cin, p3);         // p3 = "user/docs"
```

注意第一次 getline 得到空字符串，因为 `/` 之前没有内容。

## 示例：连续分隔符解析

假设输入为 `red,green,blue`，程序执行：

```cpp
string a, b, c;
getline(cin, a, ',');   // a = "red"
getline(cin, b, ',');   // b = "green"
getline(cin, c);         // c = "blue"
```

关键点：每次 getline 从上次停止的位置继续读取。

## 与 ifstream 配合

getline 同样适用于文件流：

```cpp
ifstream fin("data.txt");
string line;
getline(fin, line);       // 从文件读一行
getline(fin, line, '/');  // 从文件读到 '/'
```



# ifstream 与命令行参数

## 命令行参数

程序通过 `main` 函数的参数获取命令行输入：

```cpp
int main(int argc, char* argv[]) {
    // argc = 参数个数（包括程序名本身）
    // argv[0] = 程序名，如 "./fileread"
    // argv[1] = 第一个参数，如 "students.txt"
    // argv[2] = 第二个参数（如果有）
}
```

示例：运行 `./fileread students.txt`
- `argc` = 2
- `argv[0]` = `"./fileread"`
- `argv[1]` = `"students.txt"`

## ifstream 打开文件

```cpp
#include <fstream>

ifstream fin("students.txt");   // 用文件名打开
// 或者用变量/参数
ifstream fin(argv[1]);          // 用命令行参数打开

if (!fin) {
    cout << "文件打开失败" << endl;
    return 1;
}
```

## >> 读取结构化数据

`ifstream` 的 `>>` 和 `cin` 完全一致：

```cpp
ifstream fin("data.txt");
string name;
int id, age;

// 逐词读取，自动跳过空白
fin >> name >> id >> age;

// 循环读取所有记录
while (fin >> name >> id >> age) {
    cout << name << " " << id << " " << age << endl;
}
```

`>>` 会自动跳过空格和换行符，适合读取格式规整的文本数据。




# const 引用与 auto

## const 引用

const 引用是只读的引用，不能通过它修改原变量：

int x = 10;
const int& ref = x;   // ref 是 x 的只读别名
cout << ref;           // 可以读取
ref = 20;              // 错误！不能修改

## const auto& 遍历

遍历容器时，const auto& 避免元素拷贝且保证安全：

vector<string> names = {"Alice", "Bob", "Charlie"};

for (const auto& name : names) {
    cout << name << endl;       // 不拷贝，直接引用原字符串
    // name = "x";              // 错误！const 不能修改
}

对比：
- for (auto x : vec)       — 拷贝每个元素（简单类型OK）
- for (auto& x : vec)      — 引用，可修改原元素
- for (const auto& x : vec)— 常量引用，不拷贝不修改（推荐用于string等大对象）

## string::size()

返回字符串的长度（字符数）：

string s = "hello";
cout << s.size();   // 5


# vector 与文件读写

## vector<string> 简介

`vector<string>` 是一个可以动态增长字符串数组，能存储任意数量的字符串元素。

基本操作：

```cpp
#include <vector>
#include <string>

vector<string> lines;       // 声明一个空的 string 向量
lines.push_back("hello");   // 在末尾添加一个元素
lines.size();               // 返回当前元素个数
```

## getline 读取一行

`getline` 从输入流中读取一整行内容（遇到换行符停止），存入 string 变量：

```cpp
string line;
getline(cin, line);    // 从标准输入读取一行到 line
```

当 `getline` 与文件流对象配合使用时，可以用同样的方式逐行读取文件内容。

## 文件流基础

需要包含头文件 `<fstream>`。

**打开文件用于读取：**

```cpp
ifstream fin("input.txt");
if (!fin) {
    cout << "文件打开失败" << endl;
    return 1;
}
```

**打开文件用于写入：**

```cpp
ofstream fout("output.txt");
```

使用完毕后调用 `fin.close()` 和 `fout.close()` 关闭文件。

## 遍历 vector

vector 可以通过下标访问元素，下标从 0 开始：

```cpp
// 正序遍历
for (int i = 0; i < lines.size(); i++) {
    cout << lines[i] << endl;
}
```

通过调整下标的起始值和变化方向，可以实现不同的遍历顺序，例如从最后一个元素向前遍历。

## 逆序遍历示例

以下示例展示了如何逆序输出一个 `vector<int>` 的内容：

```cpp
#include <iostream>
#include <vector>
using namespace std;

int main() {
    vector<int> nums = {10, 20, 30, 40};

    // 方法1: 下标从 size()-1 递减到 0
    for (int i = nums.size() - 1; i >= 0; i--) {
        cout << nums[i] << " ";
    }
    // 输出: 40 30 20 10

    return 0;
}
```

关键点：
- `nums.size() - 1` 是最后一个元素的下标
- 循环条件是 `i >= 0`，每轮 `i--`
- 同样的方法适用于 `vector<string>`

## 思路提示

将文件读入 vector 后逆序写出的任务，需要将以上知识点组合使用：
1. 用 `ifstream` 打开输入文件
2. 用 `getline` 逐行读取，存入 `vector<string>`
3. 对 vector 进行逆序遍历
4. 用 `ofstream` 将结果写入输出文件



# getline 与 string 查找

## getline 读取整行

```cpp
string line;
getline(cin, line);   // 读取一整行，包括空格
```

循环读取直到输入结束：
```cpp
while (getline(cin, line)) {
    // 处理 line
}
```

## string::find 查找子串

`find()` 返回子串在字符串中的起始位置，未找到时返回 `string::npos`：

```cpp
string s = "c++ is great";
size_t pos = s.find("c++");     // pos = 0（找到，在第0位）
size_t pos2 = s.find("python"); // pos2 = string::npos（未找到）

// 判断是否包含子串：
if (s.find("c++") != string::npos) {
    cout << "包含 c++" << endl;
}
```

## ofstream 写入文件

```cpp
#include <fstream>
ofstream fout("output.txt");
fout << "Hello" << endl;   // 和 cout << 用法一样
fout.close();
```

## 字符串比较

判断输入是否为结束标记：
```cpp
if (line == "END") break;
```

`==` 比较两个字符串的内容是否完全相同（大小写敏感）。



# 引用参数传递

## 什么是引用

引用是变量的别名，操作引用就是操作原变量：

```cpp
int x = 10;
int& ref = x;    // ref 是 x 的引用（别名）
ref = 20;        // x 也变成 20
```

声明引用时使用 `&` 符号，引用必须在声明时初始化，且不能重新绑定到其他变量。

## 值传递 vs 引用传递

函数参数的传递方式决定了函数内部能否修改调用者的变量。

**值传递**（不改变原变量）：

```cpp
void doubleValue(int x) {
    x = x * 2;    // x 是副本，修改不影响原变量
}

int a = 5;
doubleValue(a);   // a 仍然是 5
```

**引用传递**（改变原变量）：

```cpp
void doubleValue(int& x) {
    x = x * 2;    // x 是原变量的引用，修改会影响原变量
}

int a = 5;
doubleValue(a);   // a 变成 10
```

两者的关键区别在于参数类型：`int x` 接收的是值的副本，`int& x` 接收的是原变量的引用。

## 交换两个变量

交换两个变量的值是引用传递的经典应用场景。

要交换两个变量 `a` 和 `b` 的值，你需要借助一个**临时变量**来暂存其中一个值，否则在赋值过程中会丢失数据。

基本思路如下：

1. 用临时变量保存其中一个变量的值
2. 将另一个变量的值赋给第一个变量
3. 将临时变量中保存的值赋给第二个变量

关键在于：如果你想让函数真正交换调用者的两个变量，函数参数必须使用**引用传递**。如果使用值传递，函数只会交换副本，原变量不会发生任何变化。

请根据以上提示，自行编写一个 `swap` 函数，并在 `main` 函数中测试验证交换是否成功。




# 类与对象

## 类的基本概念

**类**（Class）是用户自定义的数据类型，它将数据（成员变量）和操作数据的方法（成员函数）封装在一起。

**对象**（Object）是类的一个实例，是类创建的具体变量。

## 类的定义格式

```cpp
class ClassName {
private:    // 私有成员，只能在类内访问
    // 成员变量

public:     // 公有成员，可以在类外访问
    // 构造函数
    // 成员函数
};
```

## 访问控制

| 关键字 | 访问范围 |
|--------|----------|
| `private` | 仅类内部可访问 |
| `public` | 任何地方可访问 |
| `protected` | 类内部和派生类可访问 |

## 构造函数

**构造函数**是一种特殊的成员函数：
- 名字与类名相同
- 没有返回类型
- 创建对象时自动调用
- 用于初始化成员变量

```cpp
class Triangle {
private:
    int side1, side2, side3;

public:
    // 构造函数
    Triangle(int s1, int s2, int s3) {
        side1 = s1;
        side2 = s2;
        side3 = s3;
    }
};
```

## 成员函数

成员函数定义在类内部或外部，用于操作类的私有成员。

```cpp
class Triangle {
private:
    int side1, side2, side3;

public:
    Triangle(int s1, int s2, int s3) { /*...*/ }

    // 成员函数
    int getPerimeter() {
        return side1 + side2 + side3;
    }
};
```

## 创建对象

```cpp
// 使用普通方式创建对象
Triangle tri(3, 4, 5);

// 调用成员函数
int perimeter = tri.getPerimeter();
```

## 重要规则

1. **类定义末尾必须有分号**：`};`
2. **成员函数可以访问私有成员**
3. **私有成员只能在类内访问**
4. **使用构造函数初始化对象**
5. **每个对象有自己的成员变量存储空间**



# 类与封装

## 类的基本结构

```cpp
class ClassName {
private:    // 私有成员，只能在类内访问
    // 成员变量

public:     // 公有成员，可以在类外访问
    // 构造函数
    // 成员函数
};
```

## 访问控制

| 关键字 | 访问范围 |
|--------|----------|
| `private` | 仅类内部可访问 |
| `public` | 任何地方可访问 |
| `protected` | 类内部和派生类可访问 |

## 封装的意义

**为什么需要封装？**
- 保护数据：防止外部随意修改
- 控制访问：通过方法控制如何修改数据
- 隐藏实现：外部不需要知道内部实现细节

## 示例：股票类

```cpp
class Stock {
private:
    std::string company;
    long shares;
    double share_val;
    double total_val;
    void set_tot() { total_val = shares * share_val; }

public:
    Stock(const string &co, long n, double pr) {
        company = co;
        if (n < 0) {
            cerr << "份额不能为负数：" << company << "设为0.\n";
            shares = 0;
        } else {
            shares = n;
        }
        share_val = pr;
        set_tot();
    }
};
```

## 重要规则

1. **类定义末尾必须有分号**：`};`
2. **成员函数可以访问私有成员**
3. **私有成员只能在类内访问**
4. **使用构造函数初始化对象**



# 类与封装

## 类的基本结构

```cpp
class ClassName {
private:    // 私有成员，只能在类内访问
    // 成员变量

public:     // 公有成员，可以在类外访问
    // 构造函数
    // 成员函数
};
```
# 类与对象

## 类的基本概念

**类**（Class）是用户自定义的数据类型，它将数据（成员变量）和操作数据的方法（成员函数）封装在一起。

**对象**（Object）是类的一个实例，是类创建的具体变量。

## 类的定义格式

```cpp
class ClassName {
private:    // 私有成员，只能在类内访问
    // 成员变量

public:     // 公有成员，可以在类外访问
    // 构造函数
    // 成员函数
};
```

## 访问控制

| 关键字 | 访问范围 |
|--------|----------|
| `private` | 仅类内部可访问 |
| `public` | 任何地方可访问 |
| `protected` | 类内部和派生类可访问 |

## 构造函数

**构造函数**是一种特殊的成员函数：
- 名字与类名相同
- 没有返回类型
- 创建对象时自动调用
- 用于初始化成员变量

```cpp
class Triangle {
private:
    int side1, side2, side3;

public:
    // 构造函数
    Triangle(int s1, int s2, int s3) {
        side1 = s1;
        side2 = s2;
        side3 = s3;
    }
};
```

## 成员函数

成员函数定义在类内部或外部，用于操作类的私有成员。

```cpp
class Triangle {
private:
    int side1, side2, side3;

public:
    Triangle(int s1, int s2, int s3) { /*...*/ }

    // 成员函数
    int getPerimeter() {
        return side1 + side2 + side3;
    }
};
```

## 创建对象

```cpp
// 使用普通方式创建对象
Triangle tri(3, 4, 5);

// 调用成员函数
int perimeter = tri.getPerimeter();
```

## 重要规则

1. **类定义末尾必须有分号**：`};`
2. **成员函数可以访问私有成员**
3. **私有成员只能在类内访问**
4. **使用构造函数初始化对象**
5. **每个对象有自己的成员变量存储空间**

# 类与对象

## 类的基本概念

**类**（Class）是用户自定义的数据类型，它将数据（成员变量）和操作数据的方法（成员函数）封装在一起。

**对象**（Object）是类的一个实例，是类创建的具体变量。

## 类的定义格式

```cpp
class ClassName {
private:    // 私有成员，只能在类内访问
    // 成员变量

public:     // 公有成员，可以在类外访问
    // 构造函数
    // 成员函数
};
```

## 访问控制

| 关键字 | 访问范围 |
|--------|----------|
| `private` | 仅类内部可访问 |
| `public` | 任何地方可访问 |
| `protected` | 类内部和派生类可访问 |

## 构造函数

**构造函数**是一种特殊的成员函数：
- 名字与类名相同
- 没有返回类型
- 创建对象时自动调用
- 用于初始化成员变量

```cpp
class Triangle {
private:
    int side1, side2, side3;

public:
    // 构造函数
    Triangle(int s1, int s2, int s3) {
        side1 = s1;
        side2 = s2;
        side3 = s3;
    }
};
```

## 成员函数

成员函数定义在类内部或外部，用于操作类的私有成员。

```cpp
class Triangle {
private:
    int side1, side2, side3;

public:
    Triangle(int s1, int s2, int s3) { /*...*/ }

    // 成员函数
    int getPerimeter() {
        return side1 + side2 + side3;
    }
};
```

## 创建对象

```cpp
// 使用普通方式创建对象
Triangle tri(3, 4, 5);

// 调用成员函数
int perimeter = tri.getPerimeter();
```

## 重要规则

1. **类定义末尾必须有分号**：`};`
2. **成员函数可以访问私有成员**
3. **私有成员只能在类内访问**
4. **使用构造函数初始化对象**
5. **每个对象有自己的成员变量存储空间**

# 类与封装

## 类的基本结构

```cpp
class ClassName {
private:    // 私有成员，只能在类内访问
    // 成员变量

public:     // 公有成员，可以在类外访问
    // 构造函数
    // 成员函数
};
```

## 访问控制

| 关键字 | 访问范围 |
|--------|----------|
| `private` | 仅类内部可访问 |
| `public` | 任何地方可访问 |
| `protected` | 类内部和派生类可访问 |

## 封装的意义

**为什么需要封装？**
- 保护数据：防止外部随意修改
- 控制访问：通过方法控制如何修改数据
- 隐藏实现：外部不需要知道内部实现细节

## 示例：股票类

```cpp
class Stock {
private:
    std::string company;
    long shares;
    double share_val;
    double total_val;
    void set_tot() { total_val = shares * share_val; }

public:
    Stock(const string &co, long n, double pr) {
        company = co;
        if (n < 0) {
            cerr << "份额不能为负数：" << company << "设为0.\n";
            shares = 0;
        } else {
            shares = n;
        }
        share_val = pr;
        set_tot();
    }
};
```

## 重要规则

1. **类定义末尾必须有分号**：`};`
2. **成员函数可以访问私有成员**
3. **私有成员只能在类内访问**
4. **使用构造函数初始化对象**

# 类与封装

## 类的基本结构

```cpp
class ClassName {
private:    // 私有成员，只能在类内访问
    // 成员变量

public:     // 公有成员，可以在类外访问
    // 构造函数
    // 成员函数
};
```

## 访问控制

| 关键字 | 访问范围 |
|--------|----------|
| `private` | 仅类内部可访问 |
| `public` | 任何地方可访问 |
| `protected` | 类内部和派生类可访问 |

## 封装的意义

**为什么需要封装？**
- 保护数据：防止外部随意修改
- 控制访问：通过方法控制如何修改数据
- 隐藏实现：外部不需要知道内部实现细节

## 示例：股票类

```cpp
class Stock {
private:
    std::string company;
    long shares;
    double share_val;
    double total_val;
    void set_tot() { total_val = shares * share_val; }

public:
    Stock(const string &co, long n, double pr) {
        company = co;
        if (n < 0) {
            cerr << "份额不能为负数：" << company << "设为0.\n";
            shares = 0;
        } else {
            shares = n;
        }
        share_val = pr;
        set_tot();
    }
};
```

## 重要规则

1. **类定义末尾必须有分号**：`};`
2. **成员函数可以访问私有成员**
3. **私有成员只能在类内访问**
4. **使用构造函数初始化对象**

- 类的定义：class、private、public
- 封装：数据隐藏，通过方法访问
- 构造函数：初始化对象
- 访问控制：private 成员只能在类内访问

【知识点】
- 类的设计：如何组织数据和方法
- 数组成员：类中使用数组存储多个数据
- 成员函数：实现添加、计算、查询等功能
- 构造函数：初始化成员变量


## 访问控制

| 关键字 | 访问范围 |
|--------|----------|
| `private` | 仅类内部可访问 |
| `public` | 任何地方可访问 |
| `protected` | 类内部和派生类可访问 |

## 封装的意义

**为什么需要封装？**
- 保护数据：防止外部随意修改
- 控制访问：通过方法控制如何修改数据
- 隐藏实现：外部不需要知道内部实现细节

## 示例：股票类

```cpp
class Stock {
private:
    std::string company;
    long shares;
    double share_val;
    double total_val;
    void set_tot() { total_val = shares * share_val; }

public:
    Stock(const string &co, long n, double pr) {
        company = co;
        if (n < 0) {
            cerr << "份额不能为负数：" << company << "设为0.\n";
            shares = 0;
        } else {
            shares = n;
        }
        share_val = pr;
        set_tot();
    }
};
```

## 重要规则

1. **类定义末尾必须有分号**：`};`
2. **成员函数可以访问私有成员**
3. **私有成员只能在类内访问**
4. **使用构造函数初始化对象**



# C++类与对象 - 知识点速览

## 类的基本结构

```cpp
class ClassName {
private:    // 私有成员，只能在类内访问
    // 成员变量

public:     // 公有成员，可以在类外访问
    // 构造函数
    // 成员函数
};
```
// TODO 2: 定义构造函数（使用初始化列表）
    // 函数签名：Rectangle(double l, double w)
    // 提示：使用初始化列表格式：
    //   Rectangle(double l, double w) : length(l), width(w) { }

# C++类的基础知识点（大学生学习版）

针对大学生初学者，以下是C++类的核心基础知识点，按学习顺序整理：

## 一、类的基本概念

### 1.1 什么是类？
- **类（Class）**：用户自定义的数据类型，将数据和操作数据的函数封装在一起
- **对象（Object）**：类的实例，具有类定义的属性和行为

### 1.2 类的定义格式
```cpp
class Student {  // 类名首字母大写（命名规范）
private:         // 私有成员：只能在类内部访问
    int id;
    string name;
    double score;
    
public:          // 公有成员：可以在类外部访问
    // 成员函数（方法）
    void setId(int i) { id = i; }
    int getId() const { return id; }
    
    void setName(string n) { name = n; }
    string getName() const { return name; }
    
    void setScore(double s) { score = s; }
    double getScore() const { return score; }
};
```

### 1.3 访问限定符
| 访问限定符 | 访问范围 | 说明 |
|-----------|---------|------|
| **private** | 类内部 | 默认访问权限，封装数据 |
| **protected** | 类内部 + 派生类 | 继承时使用 |
| **public** | 任何地方 | 对外接口 |

**记忆口诀**：私有数据，公有接口

## 二、对象的创建与使用

### 2.1 创建对象
```cpp
// 方式1：栈上创建（推荐初学者使用）
Student stu1;
stu1.setId(1001);
stu1.setName("张三");
stu1.setScore(95.5);

// 方式2：堆上创建（需要手动释放）
Student* stu2 = new Student();
stu2->setId(1002);
stu2->setName("李四");
delete stu2;  // 一定要释放！

// 方式3：初始化时赋值
Student stu3;
stu3.setId(1003);
stu3.setName("王五");
stu3.setScore(88.0);
```

### 2.2 访问成员
```cpp
// 对象访问：使用点运算符 .
stu1.setId(1001);

// 指针访问：使用箭头运算符 ->
Student* pStu = &stu1;
pStu->setId(1002);
```

## 三、构造函数（Constructor）

### 3.1 什么是构造函数？
- **特殊成员函数**：创建对象时自动调用
- **作用**：初始化对象的数据成员
- **特点**：函数名与类名相同，无返回值类型

### 3.2 构造函数类型

#### 无参构造函数
```cpp
class Student {
public:
    Student() {  // 无参构造
        id = 0;
        name = "未知";
        score = 0.0;
    }
    
private:
    int id;
    string name;
    double score;
};

// 使用
Student stu;  // 自动调用无参构造
```

#### 带参构造函数
```cpp
class Student {
public:
    Student(int i, string n, double s) {
        id = i;
        name = n;
        score = s;
    }
    
private:
    int id;
    string name;
    double score;
};

// 使用
Student stu(1001, "张三", 95.5);  // 创建时直接初始化
```

#### 全缺省构造函数
```cpp
class Student {
public:
    Student(int i = 0, string n = "未知", double s = 0.0) {
        id = i;
        name = n;
        score = s;
    }
    
private:
    int id;
    string name;
    double score;
};

// 使用
Student stu1;                    // 使用默认值
Student stu2(1001);              // 只指定id
Student stu3(1001, "张三");      // 指定id和name
Student stu4(1001, "张三", 95.5); // 指定所有参数
```

### 3.3 初始化列表（重要！）
```cpp
class Student {
public:
    // 推荐写法：使用初始化列表
    Student(int i, string n, double s) 
        : id(i), name(n), score(s) {  // 初始化列表
        // 构造函数体（可以为空）
    }
    
private:
    int id;
    string name;
    double score;
};
```

**为什么用初始化列表？**
- 效率更高（直接初始化，不是先默认再赋值）
- const成员和引用成员必须用初始化列表

## 四、析构函数（Destructor）

### 4.1 什么是析构函数？
- **特殊成员函数**：对象销毁时自动调用
- **作用**：清理对象占用的资源（如释放内存）
- **特点**：函数名是类名前加`~`，无参数，无返回值

### 4.2 析构函数示例
```cpp
class Student {
public:
    Student(int i, string n) {
        id = i;
        name = n;
        cout << "构造函数被调用，创建学生: " << name << endl;
    }
    
    ~Student() {  // 析构函数
        cout << "析构函数被调用，销毁学生: " << name << endl;
    }
    
private:
    int id;
    string name;
};

// 使用
void test() {
    Student stu1(1001, "张三");  // 调用构造
    {  // 新作用域
        Student stu2(1002, "李四");  // 调用构造
    }  // stu2离开作用域，调用析构
}  // stu1离开作用域，调用析构
```

## 五、this指针

### 5.1 什么是this指针？
- **隐含参数**：每个非静态成员函数都有一个隐藏的this指针
- **指向**：当前调用该成员函数的对象
- **类型**：`类名* const`（指向当前类对象的常量指针）

### 5.2 this指针的作用
```cpp
class Student {
public:
    void setId(int id) {
        // this->id 指向当前对象的id成员
        // id 是函数参数
        this->id = id;  // 区分同名的成员变量和参数
    }
    
    // 返回当前对象的引用（支持链式调用）
    Student& setName(string name) {
        this->name = name;
        return *this;  // 返回当前对象的引用
    }
    
private:
    int id;
    string name;
};

// 使用
Student stu;
stu.setId(1001).setName("张三");  // 链式调用
```

## 六、const成员函数

### 6.1 什么是const成员函数？
- **承诺**：该函数不会修改对象的数据成员
- **位置**：在函数声明和定义的末尾加`const`

### 6.2 const成员函数示例
```cpp
class Student {
public:
    // const成员函数：承诺不修改对象
    string getName() const {
        return name;  // 只读取，不修改
    }
    
    double getScore() const {
        return score;
    }
    
    // 非const成员函数：可以修改对象
    void setScore(double s) {
        score = s;
    }
    
private:
    int id;
    string name;
    double score;
};

// 使用
const Student stu(1001, "张三", 95.5);
string n = stu.getName();  // OK：const对象只能调用const成员函数
// stu.setScore(90.0);     // 错误：const对象不能调用非const成员函数
```

## 七、静态成员（Static）

### 7.1 静态成员变量
```cpp
class Student {
public:
    static int count;  // 静态成员变量声明
    
    Student(string n) {
        name = n;
        count++;  // 每创建一个对象，计数器加1
    }
    
    static int getCount() {  // 静态成员函数
        return count;
    }
    
private:
    string name;
    static int count;  // 声明
};

// 静态成员变量必须在类外定义和初始化
int Student::count = 0;

// 使用
Student stu1("张三");
Student stu2("李四");
cout << "学生总数: " << Student::count << endl;  // 2
cout << "学生总数: " << Student::getCount() << endl;  // 2
```

**静态成员特点：**
- 所有对象共享同一份数据
- 不属于任何特定对象
- 可以通过类名直接访问

## 八、简单的继承

### 8.1 什么是继承？
- **继承**：一个类（派生类）获得另一个类（基类）的属性和方法
- **作用**：代码复用，建立类之间的层次关系

### 8.2 继承示例
```cpp
// 基类（父类）
class Person {
public:
    Person(string n, int a) : name(n), age(a) {}
    
    void introduce() const {
        cout << "姓名: " << name << ", 年龄: " << age << endl;
    }
    
protected:  // 保护成员：派生类可以访问
    string name;
    int age;
};

// 派生类（子类）：公有继承
class Student : public Person {
public:
    Student(string n, int a, int i) 
        : Person(n, a), id(i) {}  // 调用基类构造函数
    
    void study() const {
        cout << name << "正在学习" << endl;  // 可以访问基类的保护成员
    }
    
    void showInfo() const {
        introduce();  // 调用基类的成员函数
        cout << "学号: " << id << endl;
    }
    
private:
    int id;
};

// 使用
Student stu("张三", 20, 1001);
stu.introduce();  // 调用继承的基类函数
stu.study();      // 调用自己的函数
stu.showInfo();   // 调用自己的函数
```

## 九、运算符重载基础

### 9.1 什么是运算符重载？
- **重载**：为已有的运算符赋予新的含义
- **目的**：让自定义类型可以像内置类型一样使用运算符

### 9.2 常见运算符重载
```cpp
class Complex {
public:
    Complex(double r = 0.0, double i = 0.0) : real(r), imag(i) {}
    
    // + 运算符重载（成员函数）
    Complex operator+(const Complex& other) const {
        return Complex(real + other.real, imag + other.imag);
    }
    
    // << 运算符重载（友元函数，用于输出）
    friend ostream& operator<<(ostream& os, const Complex& c) {
        os << c.real;
        if (c.imag >= 0) os << "+";
        if (c.imag != 0) os << c.imag << "i";
        return os;
    }
    
private:
    double real;
    double imag;
};

// 使用
Complex c1(3.0, 4.0);
Complex c2(1.0, 2.0);
Complex c3 = c1 + c2;  // 使用重载的+运算符
cout << c3 << endl;    // 输出: 4+6i
```

## 十、学习建议与练习

### 10.1 学习路径
1. **理解概念**：类、对象、封装
2. **掌握语法**：构造函数、析构函数、成员函数
3. **实践应用**：创建简单类（如学生、图书、矩形等）
4. **深入学习**：继承、多态、运算符重载

### 10.2 经典练习题
1. **设计一个矩形类**：包含长、宽，计算面积和周长
2. **设计一个学生类**：包含学号、姓名、成绩，计算平均分
3. **设计一个日期类**：包含年、月、日，判断是否为闰年
4. **设计一个银行账户类**：包含账号、余额，实现存款、取款功能

### 10.3 常见错误提醒
```cpp
// 错误1：忘记定义静态成员变量
class Test {
    static int count;
};
// int Test::count = 0;  // 必须在类外定义！

// 错误2：构造函数写成void类型
class Test {
    void Test() {}  // 错误！构造函数不能有返回类型
};

// 错误3：析构函数有参数
class Test {
    ~Test(int x) {}  // 错误！析构函数不能有参数
};
```

---

以上是C++类的基础知识点，适合大学生初学者循序渐进地学习。建议先理解概念，再通过大量练习巩固知识！



## 头文件保护

防止头文件被重复包含：

```cpp
#ifndef CLASSNAME_H
#define CLASSNAME_H

// 类的定义

#endif
```

## 成员访问控制

| 关键字 | 访问范围 |
|--------|----------|
| `private` | 仅类内部可访问 |
| `public` | 任何地方可访问 |
| `protected` | 类内部和派生类可访问 |

## 构造函数

- 与类同名，无返回值
- 创建对象时自动调用
- 用于初始化成员变量

```cpp
class ClassName {
public:
    ClassName(参数列表) {
        // 初始化代码
    }
};
```

## 成员函数

- 定义在类内部的函数
- 可以访问类的私有成员

```cpp
class ClassName {
private:
    int value;

public:
    int getValue() {
        return value;  // 访问私有成员
    }
};
```

## 分离编译

头文件(.h)声明，源文件(.cpp)实现：

```cpp
// rectangle.h
#ifndef RECTANGLE_H
#define RECTANGLE_H

class Rectangle {
private:
    double width;
    double height;

public:
    Rectangle(double w, double h);
    double getArea();
};

#endif

// rectangle.cpp
#include "rectangle.h"

Rectangle::Rectangle(double w, double h) {
    width = w;
    height = h;
}

double Rectangle::getArea() {
    return width * height;
}
```

## 使用类

```cpp
#include "rectangle.h"

int main() {
    Rectangle rect(5.0, 3.0);
    double area = rect.getArea();
    return 0;
}
```

## 常见注意事项

1. **类定义末尾必须有分号**
2. **使用头文件保护**，避免重复包含
3. **包含正确的头文件**，使用 `#include "filename.h"`
4. **构造函数初始化**成员变量

---

**提示**: 本实验需要实现 Circle 类，参考 Rectangle 类的结构，但计算逻辑不同。



# C++ 引用传递

## 什么是引用？

引用（Reference）是变量的"别名"，与原变量指向同一内存空间。

```cpp
int num = 10;
int& ref = num;  // ref 是 num 的别名
ref = 20;        // num 的值也变成 20
```

## 引用作为函数参数

使用引用作为函数参数，可以直接修改函数外部的变量：

```cpp
void modify(int& x) {  // x 是外部变量的引用
    x = 100;           // 修改 x 就是修改外部变量
}

int main() {
    int a = 5;
    modify(a);         // 传递 a，不需要 &
    cout << a;         // 输出：100
}
```

## 引用 vs 指针

| 特性 | 引用 | 指针 |
|------|------|------|
| 声明 | `int& ref` | `int* ptr` |
| 调用 | `func(a)` | `func(&a)` |
| 使用 | `ref = 10` | `*ptr = 10` |
| 初始化 | 必须初始化 | 可以为空 |

## 常见应用

**交换两个变量**：
```cpp
void swap(int& a, int& b) {
    int temp = a;
    a = b;
    b = temp;
}
```

**重要规则**：
1. 引用声明时必须初始化
2. 引用一旦绑定，不能改变指向
3. 引用没有空引用（NULL）的概念


# new 与 delete

## 动态对象创建

使用 `new` 运算符在堆上动态创建对象：

```cpp
Counter* ptr = new Counter(5);  // 创建对象，返回指针
```

## 访问动态对象的成员

使用 `->` 运算符访问成员：

```cpp
ptr->increment();           // 调用方法
int value = ptr->getValue(); // 获取值
```

## 释放对象

使用 `delete` 运算符释放动态创建的对象：

```cpp
delete ptr;  // 释放对象
ptr = nullptr;  // 避免悬空指针（推荐）
```

## 完整示例

```cpp
class Counter {
private:
    int value;

public:
    Counter(int v) : value(v) { }

    void increment() {
        value++;
    }

    int getValue() {
        return value;
    }
};

int main() {
    // 动态创建对象
    Counter* ptr = new Counter(5);

    // 访问成员
    cout << "初始值: " << ptr->getValue() << endl;
    ptr->increment();
    ptr->increment();
    cout << "增加后: " << ptr->getValue() << endl;

    // 释放对象
    delete ptr;
    ptr = nullptr;

    cout << "对象已释放" << endl;
    return 0;
}
```

## new vs 普通创建

| 特性 | 普通创建 | 动态创建 (new) |
|------|----------|----------------|
| 存储位置 | 栈 | 堆 |
| 生命周期 | 函数结束时自动销毁 | 必须手动 delete |
| 访问方式 | `obj.method()` | `ptr->method()` |
| 使用场景 | 对象大小固定、生命周期短 | 对象大小不确定、需要跨函数 |

## 重要规则

1. **配对使用**：每个 new 必须对应一个 delete
2. **避免悬空指针**：delete 后将指针设为 nullptr
3. **防止内存泄漏**：忘记 delete 会导致内存泄漏
4. **数组释放**：使用 `new[]` 创建的数组用 `delete[]` 释放

## 数组的动态创建与释放

```cpp
// 创建动态数组
Counter* arr = new Counter[5];

// 释放动态数组
delete[] arr;
```

## 安全做法

```cpp
// 使用后置 nullptr 防止悬空指针
delete ptr;
ptr = nullptr;

// 检查指针是否有效
if (ptr != nullptr) {
    ptr->increment();
}
```

用合理的顺序全部整理，提练所有相关知识点。也可拓展一些文本中尚未提及的知识，对于大学生学习范畴。