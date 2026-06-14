*Ideas for breaking the steady-state equalibrium in cyto*

Currently there are only 2 meaningful sources of equalibrium disruption in cyto:
1. Low-probability mutation
2. Player input (disconnecting/deleting cells)

In order to speed up evolution by natural selection, cells need more pressure from their environment.
Increased pressure (resulting in death of the unfit) would free up chemical resources for the fit to use to replicate.
In the current simulation, pressure is so low that cells are mutating excessively without any consequences, and thus their genes are effectively meaningless to their survival.
Additionally, there appears to be a problem with how cells access environmental resources, in that they don't all get an equal of what's made available in the environment. This would be undetectable if it were random which cells got access, but it appears to be the same cells soaking up the chemicals and dividing (even though their daughter cells arguably should have the exact same genome and therefore the exact same access). It would also be fine if a genetic advantage were allowing the cells to get preferential access, but the daughter cell problem also refutes that being the case.

Proposals:
1. 1000x all chemicals. Going below 1000 biomass results in death. Cell radius is scaled down such that 1000 biomass now is equivalent to 1 biomass before. 1000x decay rates for biomass.
2. When biomass decays, the smaller chemical is ejected into the environment - the larger one is retained in the cytoplasm.
3. When multiple genes are active at once, they compete with one another for the finite resources the cell has available (regardless of whether they actually use the same energy source). 2 genes active means each gene can only utilize 50% of the available energy it relies on. 5 genes -> 20% cap.
-- Note that if 2 genes code for the same activity, then it's nearly a wash: 
--- If light exposure is 501, and 2 active genes use light to bind "ab", then each gene will be able to use 250 light for binding even though the action is identical (remainder is lost).
--- If light exposure is 500 and "ba" present is 100, and 1 active gene uses light to bind "ab", and another active gene breaks "ba" to bind "ab", then the first gene can only use 250 light, and the second gene can only use 50 "ba" even though the source is different.
4. Light exposure per tick in any given grid cell is split amongst all bio cells in that grid cell. This emulates shading.
