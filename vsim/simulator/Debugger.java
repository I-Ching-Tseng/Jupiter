/*
Copyright (C) 2018 Andres Castellanos

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>
*/

package vsim.simulator;

import vsim.Globals;
import vsim.Settings;
import vsim.utils.IO;
import vsim.utils.Cmd;
import vsim.utils.Data;
import vsim.riscv.Memory;
import java.util.HashMap;
import vsim.utils.Message;
import vsim.utils.Colorize;
import java.io.IOException;
import vsim.riscv.MemorySegments;
import vsim.linker.LinkedProgram;
import vsim.riscv.instructions.MachineCode;
import vsim.assembler.statements.Statement;


/**
 * The class Debugger implements a simple debugger for RISC-V programs.
 */
public final class Debugger {

  /** a linked program to debug */
  private LinkedProgram program;
  /** a history of breakpoints */
  private HashMap<Integer, Boolean> breakpoints;
  /** calculated space for pretty printed statements */
  private int space;
  /** previous command */
  private String[] args;

  /**
   * Unique constructor that takes a linked program
   *
   * @param program the linked program
   * @see vsim.linker.LinkedProgram
   */
  public Debugger(LinkedProgram program) {
    this.program = program;
    this.breakpoints = new HashMap<Integer, Boolean>();
    this.space = 1;
    this.args = null;
    for (Statement stmt: program.getStatements())
      this.space = Math.max(this.space, stmt.getDebugInfo().getSource().length());
    // set program breakpoints
    for (Integer breakpoint: program.getBreakpoints())
      this.breakpoints.put(breakpoint, true);
  }

  /**
   * This method pretty prints the debugger help message.
   */
  private void help() {
    IO.stdout.println("Available commands: " + System.getProperty("line.separator"));
    // help
    IO.stdout.println("help/?              - show this help message");
    // exit
    IO.stdout.println("exit/quit           - exit the simulator and debugger");
    // execute previous
    IO.stdout.println("!                   - execute previous command");
    // print state
    IO.stdout.println("showx               - print all RVI registers");
    IO.stdout.println("showf               - print all RVF registers");
    IO.stdout.println("print regname       - print register");
    IO.stdout.println("memory address      - print 12 x 4 cells of memory starting at address");
    IO.stdout.println("memory address rows - print rows x 4 cells of memory starting at address");
    IO.stdout.println("globals             - print global symbols");
    IO.stdout.println("locals filename     - print local symbols of a file");
    // execution and breakpoints
    IO.stdout.println("step/s               - step the program for 1 instruction");
    IO.stdout.println("continue/c           - continue program execution without stepping");
    IO.stdout.println("breakpoint/b address - set a breakpoint at address");
    IO.stdout.println("clear                - clear all breakpoints");
    IO.stdout.println("delete addr          - delete breakpoint at address");
    IO.stdout.println("list                 - list all breakpoints");
    // reset state and start again
    IO.stdout.println("reset                - reset all state (regs, memory) and start again");
  }

  /**
   * This method pretty prints the RVI register file.
   *
   * @see vsim.riscv.RVIRegisterFile
   */
  private void showx() {
    Globals.regfile.print();
  }

  /**
   * This method pretty prints the RVF register file.
   *
   * @see vsim.riscv.RVFRegisterFile
   */
  private void showf() {
    Globals.fregfile.print();
  }

  /**
   * This method tries to print a register of the RVI or RVF register file.
   *
   * @param reg the register name to print
   * @see vsim.riscv.RVIRegisterFile
   * @see vsim.riscv.RVFRegisterFile
   */
  private void print(String reg) {
    if ((Globals.regfile.getRegisterNumber(reg) != -1) || reg.equals("pc"))
      Globals.regfile.printReg(reg);
    else if (Globals.fregfile.getRegisterNumber(reg) != -1)
      Globals.fregfile.printReg(reg);
    else
      Message.error("invalid register name: " + reg);
  }

  /**
   * This methods prints a portion of the RISC-V memory
   *
   * @param address the address to start printing memory in hex or decimal
   * @param rows how many rows of 4 memory cells to print
   * @see vsim.riscv.Memory
   */
  private void memory(String address, String rows) {
    // default print 12 rows
    int n = 12;
    if (rows != null) {
      try {
        n = Integer.parseInt(rows);
        if (n < 0) {
          Message.error("number of rows should be > 0");
          return;
        }
      } catch (Exception e ) {
        Message.error("invalid number of rows: " + rows);
        return;
      }
    }
    int addr;
    try {
      if (address.startsWith("0x"))
        addr = Integer.parseInt(address.substring(2), 16);
      else
        addr = Integer.parseInt(address);
    } catch (Exception e) {
      Message.error("invalid address: " + address);
      return;
    }
    if (Memory.checkLoad(addr))
      Globals.memory.print(addr, n);
    else {
      if (Settings.QUIET)
        Message.warning("trying to print values from reserved memory (ignoring)");
    }
  }

  /**
   * This method prints the global symbol table.
   *
   * @see vsim.Globals#globl
   */
  private void globals() {
    Globals.globl.print();
  }

  /**
   * This method prints the local symbol table of a file.
   *
   * @param filename file filename
   * @see vsim.Globals#local
   */
  private void locals(String filename) {
    if (Globals.local.containsKey(filename))
      Globals.local.get(filename).print();
    else
      Message.warning("invalid filename '" + filename + "' (ignoring)");
  }

  /**
   * This method tries to step the program by one statement and pretty prints
   * debug information.
   */
  private void step() {
    Statement stmt = program.next();
    int pcVal = Globals.regfile.getProgramCounter();
    String pc = String.format("0x%08x", pcVal);
    // no more statements
    if (stmt == null) {
      Message.error("attempt to execute non-instruction at " + pc);
      return;
    }
    // print debugging info
    String space = "";
    MachineCode result = stmt.result();
    String source = stmt.getDebugInfo().getSource();
    // calculate space (pretty print)
    for (int j = 0; j < (this.space - source.length()); j++)
      space += " ";
    // format all debugging info
    IO.stdout.println(
      String.format(
        "FROM: %s",
        Colorize.yellow(stmt.getDebugInfo().getFilename())
      )
    );
    IO.stdout.println(
      String.format(
        "PC [%s] CODE:%s    %s %s» %s",
        Colorize.cyan(pc),
        result.toString(),
        Colorize.purple(source),
        space,
        Globals.iset.get(stmt.getMnemonic()).disassemble(result)
      )
    );
    // execute instruction
    Globals.iset.get(stmt.getMnemonic()).execute(result);
    // reset breakpoint
    if (this.breakpoints.containsKey(pcVal))
      this.breakpoints.put(pcVal, true);
  }

  /**
   * This method continues the program execution until a breakpoint or
   * no more available statements are found.
   */
  private void forward() {
    Statement stmt;
    int pcVal = Globals.regfile.getProgramCounter();
    while ((stmt = this.program.next()) != null) {
      // get actual program counter
      pcVal = Globals.regfile.getProgramCounter();
      // breakpoint at this point ?
      if (this.breakpoints.containsKey(pcVal) && this.breakpoints.get(pcVal)) {
        this.breakpoints.put(pcVal, false);
        return;
      }
      // execute instruction
      Globals.iset.get(stmt.getMnemonic()).execute(stmt.result());
      // reset breakpoint
      if (this.breakpoints.containsKey(pcVal))
        this.breakpoints.put(pcVal, true);
    }
    // error if no exit/exit2 ecall
    String pc = String.format("0x%08x", pcVal);
    Message.error("attempt to execute non-instruction at " + pc);
  }

  /**
   * This method tries to create a breakpoint at the given address.
   *
   * @param address the address of the breakpoint in hex or decimal
   */
  private void breakpoint(String address) {
    try {
      int addr;
      if (address.startsWith("0x"))
        addr = Integer.parseInt(address.substring(2), 16);
      else
        addr = Integer.parseInt(address);
      if (Data.isWordAligned(addr)) {
        if (Data.inRange(addr, MemorySegments.TEXT_SEGMENT_BEGIN, MemorySegments.TEXT_SEGMENT_END)) {
          if (!this.breakpoints.containsKey(addr))
            this.breakpoints.put(addr, true);
        } else
          Message.error("breakpoint address has to be inside the text segment");
      } else
        Message.error("address is not aligned to a word boundary");
    } catch (Exception e) {
      Message.error("invalid address: " + address);
    }
  }

  /**
   * This method clears all the breakpoints that user set.
   */
  private void clear() {
    this.breakpoints.clear();
    System.gc();
  }

  /**
   * This method tries to delete a breakpoint that user set.
   *
   * @param address a string representing the address in hex or decimal
   */
  private void delete(String address) {
    int addr;
    try {
      if (address.startsWith("0x"))
        addr = Integer.parseInt(address.substring(2), 16);
      else
        addr = Integer.parseInt(address);
    } catch (Exception e) {
      Message.error("invalid address: " + address);
      return;
    }
    if (this.breakpoints.containsKey(addr))
      this.breakpoints.remove(addr);
    else
      Message.warning("no breakpoint at address: " + address + " (ignoring)");
  }

  /**
   * This method lists the breakpoints that user set.
   */
  private void list() {
    if (this.breakpoints.size() > 0) {
      IO.stdout.println("Breakpoints: " + System.getProperty("line.separator"));
      for (Integer address: this.breakpoints.keySet())
        IO.stdout.println(Colorize.purple(String.format("    0x%08x", address)));
    } else
      Message.log("no breakpoints yet");
  }

  /**
   * This method resets the program and the state of the simulator.
   */
  private void reset() {
    Globals.resetState();
    this.program.reset();
  }

  /**
   * This method takes an array of arguments and tries to match this
   * with an available debug command and interprets it.
   *
   * @param args the command arguments
   */
  private void interpret(String[] args) {
    // save previous args
    if (!args[0].equals("!"))
      this.args = args;
    // exit/quit
    if ((args[0].equals("exit") || args[0].equals("quit"))) {
      if (args.length != 1)
        Message.warning("exit command does not expect any argument (ignoring)");
      System.exit(0);
    }
    // help/?
    else if ((args[0].equals("help") || args[0].equals("?"))) {
      if (args.length != 1)
        Message.warning("help command does not expect any argument (ignoring)");
      this.help();
    }
    // !
    else if (args[0].equals("!")) {
      if (args.length != 1)
        Message.warning("! command does not expect any argument (ignoring)");
      if (args != null)
        this.interpret(this.args);
    }
    // showx
    else if (args[0].equals("showx")){
      if (args.length != 1)
        Message.warning("showx command does not expect any argument (ignoring)");
      this.showx();
    }
    // showf
    else if (args[0].equals("showf")) {
      if (args.length != 1)
        Message.warning("showf command does not expect any argument (ignoring)");
      this.showf();
    }
    // print
    else if (args[0].equals("print")) {
      if (args.length == 2)
        this.print(args[1]);
      else
        Message.error("invalid usage of print cmd, valid usage 'print regname'");
    }
    // memory
    else if (args[0].equals("memory")) {
      if (args.length == 2)
        this.memory(args[1], null);
      else if (args.length == 3)
        this.memory(args[1], args[2]);
      else
        Message.error("invalid usage of memory cmd, valid usage 'memory address [rows]'");
    }
    // globals
    else if (args[0].equals("globals")) {
      if (args.length != 1)
        Message.warning("globals command does not expect any argument (ignoring)");
      this.globals();
    }
    // locals
    else if (args[0].equals("locals")) {
      if (args.length == 2)
        this.locals(args[1]);
      else
        Message.error("invalid usage of locals cmd, valid usage 'locals filename'");
    }
    // step
    else if (args[0].equals("step") || args[0].equals("s")){
      if (args.length != 1)
        Message.warning("step command does not expect any argument (ignoring)");
      this.step();
    }
    // continue
    else if (args[0].equals("continue")  || args[0].equals("c")) {
      if (args.length != 1)
        Message.warning("continue command does not expect any argument (ignoring)");
      this.forward();
    }
    // breakpoint
    else if (args[0].equals("breakpoint") || args[0].equals("b")) {
      if (args.length == 2)
        this.breakpoint(args[1]);
      else
        Message.error("invalid usage of breakpoint cmd, valid usage 'breakpoint/b address'");
    }
    // clear
    else if (args[0].equals("clear")) {
      if (args.length != 1)
        Message.warning("clear command does not expect any argument (ignoring)");
      this.clear();
    }
    // delete addr
    else if (args[0].equals("delete")) {
      if (args.length == 2)
        this.delete(args[1]);
      else
        Message.error("invalid usage of delete cmd, valid usage 'delete address'");
    }
    // list
    else if (args[0].equals("list")) {
      if (args.length != 1)
        Message.warning("list command does not expect any argument (ignoring)");
      this.list();
    }
    // reset
    else if (args[0].equals("reset")) {
      if (args.length != 1)
        Message.warning("reset command does not expect any argument (ignoring)");
      this.reset();
    }
    else
      Message.warning("unknown command '" + args[0] + "' (ignoring)");
  }

  /**
   * This method creates a command line interface that the user
   * can use to interact with the debugger.
   */
  public void run() {
    long cycles = 0;
    while (true) {
      Cmd.prompt();
      try {
        // read a line from stdin
        String line = IO.stdin.readLine();
        if (line == null) { IO.stdout.println(); continue; }
        if (line.equals("")) continue;
        // interpret line
        cycles++;
        this.interpret(line.trim().toLowerCase().split(" "));
        if (Long.remainderUnsigned(cycles, 1000) == 0)
          System.gc();
      } catch (IOException e) {
        Message.panic("input could not be read");
      } catch (Exception e) {
        Message.panic("unexpected exception");
      }
    }
  }

}