package vsim.riscv.isa.instructions.itype;


public final class Andi extends IType {

    @Override
    public int compute(int rs1, int imm) {
        return rs1 & imm;
    }

}