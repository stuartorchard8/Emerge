package org.emerge.demo.cyto.cells

enum class CellTypeGroup {
  Input,
  Transmit,
  Output,
  ;
}

enum class CellType(val dbIndex: Long, val color: Long, val group: CellTypeGroup) {
  Blank(
    0,
    0xAAAAAAFF,
    CellTypeGroup.Output,
  ),
  Muscle(
    1,
    0xDD3333FF,
    CellTypeGroup.Output,
  ),
  Quack(
    2,
    0x6666FFFF,
    CellTypeGroup.Transmit,
  ),
  Charge(
    3,
    0xEFD040FF,
    CellTypeGroup.Transmit,
  ),
  Green(
    4,
    0x40EFD0FF,
    CellTypeGroup.Transmit,
  ),
  Not(
    5,
    0xEFA040FF,
    CellTypeGroup.Transmit,
  ),
  Jump(
    6,
    0xE0E0E0FF,
    CellTypeGroup.Transmit,
  ),
  Dim(
    7,
    0x606060FF,
    CellTypeGroup.Transmit,
  ),
  Touch(
    8,
    0x2222C0FF,
    CellTypeGroup.Input,
  ),
  Sex(
    9,
    0xC022C0FF,
    CellTypeGroup.Output,
  ),
  Support(
    10,
    0xFF00FFFF,
    CellTypeGroup.Output,
  ),
  Stem(
    11,
    0xFFFFFFFF,
    CellTypeGroup.Output,
  ),
  ;

  companion object {
    fun fromDbIndex(index: Long): CellType =
      entries.firstOrNull { it.dbIndex == index } ?: Blank
  }
}
