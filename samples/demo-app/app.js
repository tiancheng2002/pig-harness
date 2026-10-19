// demo-app：极简待办清单（演示项目模式的修改任务用）
const form = document.getElementById('add-form')
const input = document.getElementById('todo-input')
const list = document.getElementById('todo-list')
const count = document.getElementById('count')

const todos = []

function render() {
  list.innerHTML = ''
  todos.forEach((todo, i) => {
    const li = document.createElement('li')
    if (todo.done) li.classList.add('done')

    const box = document.createElement('input')
    box.type = 'checkbox'
    box.checked = todo.done
    box.addEventListener('change', () => { todos[i].done = box.checked; render() })

    const label = document.createElement('span')
    label.textContent = todo.text

    const del = document.createElement('button')
    del.className = 'del'
    del.textContent = '删除'
    del.addEventListener('click', () => { todos.splice(i, 1); render() })

    li.append(box, label, del)
    list.appendChild(li)
  })
  count.textContent = todos.filter(t => !t.done).length + ' 项待办'
}

form.addEventListener('submit', (e) => {
  e.preventDefault()
  const text = input.value.trim()
  if (!text) return
  todos.push({ text, done: false })
  input.value = ''
  render()
})

render()
