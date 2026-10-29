from pathlib import Path
import hashlib
import json
import re

# Контрольные суммы получены из чтений зафиксированной версии OpenJLPT.
ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / 'assets' / 'kanji'
EXPECTED = {
    'n5': (84, '360ea239ea85db2c1c04327302f65f84f0cc4742e229b6064d3e8a9da8f3e190'),
    'n4': (169, '1a04381b74dc385f9176303508cde92be702ca8f784debc79503407960317bb2'),
}
FIELDS = {'kanji', 'onyomi', 'kunyomi', 'meaning_ru'}

# Проверка записей и сверка состава с исходными чтениями без доступа к сети.
seen = set()
for level, (count, expected_hash) in EXPECTED.items():
    rows = json.loads((DATA / f'{level}.json').read_text(encoding='utf-8'))
    if not isinstance(rows, list) or len(rows) != count:
        raise ValueError(f'{level}: неверное количество записей')

    reference = []
    for row in rows:
        if not isinstance(row, dict) or set(row) != FIELDS:
            raise ValueError(f'{level}: неверные поля записи')
        char = row['kanji']
        if not isinstance(char, str) or len(char) != 1 or not ('\u3400' <= char <= '\u9fff'):
            raise ValueError(f'{level}: неверный иероглиф {char!r}')
        if char in seen:
            raise ValueError(f'{level}: повторяется иероглиф {char}')
        seen.add(char)

        readings = []
        for field in ('onyomi', 'kunyomi'):
            values = row[field]
            if not isinstance(values, list) or any(
                not isinstance(value, str) or not re.search(r'[\u3041-\u3096\u30a1-\u30fa]', value)
                for value in values
            ):
                raise ValueError(f'{level}: неверное чтение {char}, {field}')
            readings.extend(values)
        if not readings:
            raise ValueError(f'{level}: отсутствуют чтения {char}')
        if not isinstance(row['meaning_ru'], str) or not re.search(r'[А-Яа-яЁё]', row['meaning_ru']):
            raise ValueError(f'{level}: отсутствует русское значение {char}')
        reference.append((char, row['onyomi'], row['kunyomi']))

    # Сумма учитывает каждый знак и все его чтения, но не порядок записей.
    normalized = json.dumps(sorted(reference), ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if hashlib.sha256(normalized).hexdigest() != expected_hash:
        raise ValueError(f'{level}: состав или чтения отличаются от источника')
    print(f'{level}: проверено {count} знаков')
